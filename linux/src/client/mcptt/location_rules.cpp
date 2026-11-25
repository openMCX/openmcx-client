//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

// MCPTT location rules: location based functional alias status change (TS 24.379 clause 9A.2.1.4) and rules based
// affiliation status change, by the user profile's rules (clause 9.2.1.7.2) and the group documents' geographic areas
// (clause 9.2.1.7.3). Member functions of Client (declared in client.hpp).
//
// Specifications: TS 24.379 V19.7.0, TS 24.484 V19.6.0, TS 24.481 V19.3.0 (Rel-19).

#include "client.hpp"

namespace mcx {

//------------------------------------------------------------------------------
// TS 24.484 clause 8.3.2.1 item 10: the location rules of the MCPTT user profile read (its functional aliases with
// location criteria, the affiliation rules of its group entries and the manual de-affiliation locks). The areas are
// evaluated from the next position: the first one only sets where the client is. A profile that cannot be read for
// them leaves none.
void Client::take_profile_rules(const std::string &profile) {
  try {
    mcptt_location_rules_ = parse_mcptt_profile_rules(profile);
  } catch (const std::exception &error) {
    mcptt_location_rules_ = {};
    event("profile_warning", {{"reason", std::string("location rules: ") + error.what()}});
  }
  std::erase_if(mcptt_location_rules_.functional_aliases,
                [](const auto &alias) { return !alias.has_location_criteria(); });
  mcptt_rule_area_inside_.clear();
  Json aliases = Json::array(), rules = Json::array();
  for (const auto &alias : mcptt_location_rules_.functional_aliases) aliases.push_back(alias.alias);
  for (const auto &rule : mcptt_location_rules_.affiliation_rules)
    rules.push_back({{"group", rule.group},
                     {"affiliate", rule.affiliate},
                     {"enter_areas", rule.location.enter.size()},
                     {"exit_areas", rule.location.exit.size()},
                     {"speed_or_heading_areas", rule.location.constrained},
                     {"aliases", rule.aliases}});
  status_["location_rules"] = {{"functional_aliases", aliases},
                               {"affiliation_rules", rules},
                               {"manual_deaffiliation_locked", mcptt_location_rules_.manual_deaffiliation_locked}};
}

//------------------------------------------------------------------------------
// Helper: whether the position (lat, lon) crossed into (entry) or out of (!entry) one of `areas` since the last
// position; where the client now is, for each area, is kept under `key`. With no last position nothing is crossed.
bool Client::rule_area_crossed(const std::string &key, const std::vector<location::Area> &areas, bool entry, double lat,
                               double lon) {
  bool crossed = false;
  for (std::size_t index = 0; index < areas.size(); ++index) {
    const auto slot = key + "|" + std::to_string(index);
    const bool inside = location::inside(areas[index], lat, lon);
    const auto previous = mcptt_rule_area_inside_.find(slot);
    if (previous != mcptt_rule_area_inside_.end() && previous->second != inside && inside == entry) crossed = true;
    mcptt_rule_area_inside_[slot] = inside;
  }
  return crossed;
}

//------------------------------------------------------------------------------
// The position changed: TS 24.379 clauses 9A.2.1.4, 9.2.1.7.2 and 9.2.1.7.3 in turn.
void Client::mcptt_location_rules(const location::Point &position) {
  mcptt_last_position_ = position;
  const double lat = position.lat(), lon = position.lon();
  // TS 24.379 clause 9A.2.1.4: "If a location criterion for functional alias activation or de-activation is met, the
  // MCPTT client shall initiate the functional alias status change procedure as specified in clause 9A.2.1.2". A
  // criterion is the entry into or the exit from an area (TS 24.484 clause 8.3.2.7, <EnterSpecificArea>,
  // <ExitSpecificArea>); an alias already in the state asked for is left as it is.
  for (const auto &rule : mcptt_location_rules_.functional_aliases) {
    const auto key = "fa|" + rule.alias;
    const bool activate = rule_area_crossed(key + "|activate-enter", rule.activate_on_entry, true, lat, lon) |
                          rule_area_crossed(key + "|activate-exit", rule.activate_on_exit, false, lat, lon);
    const bool deactivate = rule_area_crossed(key + "|deactivate-enter", rule.deactivate_on_entry, true, lat, lon) |
                            rule_area_crossed(key + "|deactivate-exit", rule.deactivate_on_exit, false, lat, lon);
    const bool active = (alias_publish_open_.empty() ? standing_aliases_ : aliases_wanted_).contains(rule.alias);
    const char *action = activate && !deactivate && !active  ? "activate"
                         : deactivate && !activate && active ? "deactivate"
                                                             : nullptr;
    if (!action || !authorized_) continue;
    event("functional_alias_location", {{"alias", rule.alias}, {"action", action}, {"position", {lat, lon}}});
    try {
      publish_functional_alias(action, {rule.alias}, false);
    } catch (const std::exception &error) {
      event("functional_alias_location_failed", {{"alias", rule.alias}, {"reason", error.what()}});
    }
  }
  // TS 24.379 clause 9.2.1.7.2: a rule whose location criterion the position meets (an area entered or left) is
  // fulfilled when its functional alias criterion is met too (one of its aliases active; none listed, met).
  std::map<std::string, std::pair<bool, bool>> fulfilled; // group -> (affiliate, de-affiliate)
  for (std::size_t index = 0; index < mcptt_location_rules_.affiliation_rules.size(); ++index) {
    const auto &rule = mcptt_location_rules_.affiliation_rules[index];
    const auto key = "rule|" + std::to_string(index);
    const bool crossed = rule_area_crossed(key + "|enter", rule.location.enter, true, lat, lon) |
                         rule_area_crossed(key + "|exit", rule.location.exit, false, lat, lon);
    if (crossed && rule_aliases_met(rule))
      (rule.affiliate ? fulfilled[rule.group].first : fulfilled[rule.group].second) = true;
  }
  apply_affiliation_rules(fulfilled, "location");
  mcptt_group_areas();
}

//------------------------------------------------------------------------------
// Helper: whether the functional alias criterion of a rule is met: no alias listed, or one of them active.
bool Client::rule_aliases_met(const McpttAffiliationRule &rule) const {
  return rule.aliases.empty() || std::any_of(rule.aliases.begin(), rule.aliases.end(),
                                             [&](const auto &alias) { return active_aliases_.contains(alias); });
}

//------------------------------------------------------------------------------
// TS 24.379 clause 9.2.1.7.2: the functional aliases active changed (a NOTIFY of clause 9A.2.1.3): a rule with an
// alias criterion one of whose aliases became active is fulfilled when its location criterion is met at the last
// position too (inside an area of <EnterSpecificArea>, outside one of <ExitSpecificArea>; a rule without location
// criteria, met). A rule whose location criteria all carry a speed or a heading is never met.
void Client::affiliation_rules_aliases_changed(const std::set<std::string> &before) {
  std::map<std::string, std::pair<bool, bool>> fulfilled;
  for (const auto &rule : mcptt_location_rules_.affiliation_rules) {
    if (rule.aliases.empty()) continue;
    const bool became_active = std::any_of(rule.aliases.begin(), rule.aliases.end(), [&](const auto &alias) {
      return active_aliases_.contains(alias) && !before.contains(alias);
    });
    if (!became_active) continue;
    bool location_met = rule.location.empty() && rule.location.constrained == 0;
    if (!location_met && mcptt_last_position_) {
      const double lat = mcptt_last_position_->lat(), lon = mcptt_last_position_->lon();
      location_met = std::any_of(rule.location.enter.begin(), rule.location.enter.end(),
                                 [&](const auto &area) { return location::inside(area, lat, lon); }) ||
                     std::any_of(rule.location.exit.begin(), rule.location.exit.end(),
                                 [&](const auto &area) { return !location::inside(area, lat, lon); });
    }
    if (location_met) (rule.affiliate ? fulfilled[rule.group].first : fulfilled[rule.group].second) = true;
  }
  apply_affiliation_rules(fulfilled, "functional alias");
}

//------------------------------------------------------------------------------
// Helper: TS 24.379 clause 9.2.1.7.2: "If, any defined rule is fulfilled, the MCPTT client shall initiate the
// affiliation status change procedure as specified in clause 9.2.1.2": affiliate to the groups an affiliation rule
// fulfilled names, de-affiliate from those a de-affiliation rule names (both at once for one group: nothing).
void Client::apply_affiliation_rules(const std::map<std::string, std::pair<bool, bool>> &fulfilled,
                                     const char *trigger) {
  if (!authorized_) return;
  for (const auto &[group, wanted] : fulfilled) {
    const auto [join, leave] = wanted;
    if (join == leave) continue;
    event("affiliation_rule_fulfilled", {{"group", group}, {"affiliate", join}, {"trigger", trigger}});
    try {
      if (join) {
        if (!affiliation_allowed_here(group)) {
          event("affiliation_rule_refused", {{"group", group}, {"reason", "outside the group's geographic area"}});
          continue;
        }
        if (mcptt_change_affiliation({group}, {})) rule_affiliated_groups_.insert(group);
      } else {
        mcptt_change_affiliation({}, {group});
        rule_affiliated_groups_.erase(group);
      }
    } catch (const std::exception &error) {
      event("affiliation_rule_failed", {{"group", group}, {"reason", error.what()}});
    }
  }
}

//------------------------------------------------------------------------------
// TS 24.481 clauses 7.2.4.2 and 7.2.8: keep the geographic areas of a group document read (none when it has none or
// cannot be read for them).
void Client::note_group_areas(const std::string &group, const std::string &document) {
  try {
    group_areas_[group] = parse_group_geographic_areas(document);
  } catch (const std::exception &error) {
    group_areas_.erase(group);
    event("group_document_warning", {{"group", group}, {"reason", std::string("geographic areas: ") + error.what()}});
  }
}

//------------------------------------------------------------------------------
// Helper: TS 24.379 clause 9.2.1.7.3 at the last position: whether the client may be affiliated to the group: not when
// the group document has a <permitted-geographic-area> the client is outside of ("is allowed to affiliate" only
// within it), nor a <mandatory-geographic-area> it is outside of (it "shall de-affiliate" there). Without a position
// or a document read, allowed.
bool Client::affiliation_allowed_here(const std::string &group) const {
  const auto found = group_areas_.find(group);
  if (found == group_areas_.end() || !mcptt_last_position_) return true;
  const double lat = mcptt_last_position_->lat(), lon = mcptt_last_position_->lon();
  const auto within = [&](const std::vector<location::Area> &areas) {
    return areas.empty() ||
           std::any_of(areas.begin(), areas.end(), [&](const auto &area) { return location::inside(area, lat, lon); });
  };
  return within(found->second.permitted) && within(found->second.mandatory);
}

//------------------------------------------------------------------------------
// TS 24.379 clause 9.2.1.7.3: "If the <mandatory-geographic-area> element ... is present and the MCPTT client is not
// within the area ... the MCPTT client shall de-affiliate from the group": each group of interest (the selected group
// while wanted, the monitored groups) is checked at the position, its document read when not yet. A group left so is
// affiliated again when the client is back where it may be (the user's interest in it stands; TS 37.579-2 5.11
// steps 10-12).
void Client::mcptt_group_areas() {
  if (!authorized_ || !mcptt_last_position_) return;
  std::set<std::string> candidates(monitored_groups_.begin(), monitored_groups_.end());
  if (desired_affiliation_ && !left_selected_ && !group().empty()) candidates.insert(group());
  candidates.insert(area_left_groups_.begin(), area_left_groups_.end());
  for (const auto &candidate : candidates) {
    if (!group_areas_.contains(candidate)) {
      const auto document = http_.request("GET", groups_xcap() + "/global/byGroupID/" + xcap_segment(candidate), {},
                                          {{"Authorization", bearer()}});
      if (document.status != 200) continue;
      note_group_document(candidate, document.body);
    }
    const bool allowed = affiliation_allowed_here(candidate);
    try {
      if (!allowed && !area_left_groups_.contains(candidate)) {
        event("group_area_left", {{"group", candidate}});
        mcptt_change_affiliation({}, {candidate});
        area_left_groups_.insert(candidate);
      } else if (allowed && area_left_groups_.contains(candidate)) {
        event("group_area_entered", {{"group", candidate}});
        area_left_groups_.erase(candidate);
        mcptt_change_affiliation({candidate}, {});
      }
    } catch (const std::exception &error) {
      event("group_area_affiliation_failed", {{"group", candidate}, {"reason", error.what()}});
    }
  }
  status_["groups_left_for_area"] = area_left_groups_;
}

//------------------------------------------------------------------------------
// TS 24.379 clause 9.2.1.2: "If the affiliation to the group has been activated due to a rule being fulfilled and the
// <manual-deaffiliation-not-allowed-if-affiliation-rules-are-met> element is set to a value of "true", the MCPTT client
// shall suppress the MCPTT user's request" to de-affiliate: the user is told (NOTE 1) by the command failing.
void Client::check_manual_deaffiliation(const std::vector<std::string> &groups) const {
  for (const auto &candidate : groups)
    if (rule_affiliated_groups_.contains(candidate) &&
        mcptt_location_rules_.manual_deaffiliation_locked.contains(candidate))
      throw std::runtime_error("de-affiliation from " + candidate +
                               " is not allowed while its affiliation rules are met");
}

} // namespace mcx
