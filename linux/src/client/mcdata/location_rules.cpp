//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

// MCData location rules: location based functional alias status change (TS 24.282 clause 22.2.1.4) and rules based
// affiliation status change by the MCData user profile's rules (clause 8.2.6.2), with the suppression of a manual
// de-affiliation or deactivation a rule holds (clauses 8.2.2 and 22.2.1.2). Member functions of Client (declared in
// client.hpp). The group configuration defined rules of clause 8.2.6.3 are not evaluated for MCData.
//
// Specifications: TS 24.282 V19.7.0, TS 24.484 V19.6.0 (Rel-19).

#include "client.hpp"

namespace mcx {

//------------------------------------------------------------------------------
// TS 24.484 clause 10.3.2.1 items 9 b vii and 9 i ii: the location rules of the MCData user profile read (its
// functional aliases with location criteria, the affiliation rules of its <MCDataGroupInfo> elements and the manual
// de-affiliation locks). The areas are evaluated from the next position: the first one only sets where the client is.
// A profile that cannot be read for them leaves none.
void Client::take_mcdata_profile_rules(const std::string &profile) {
  try {
    mcdata_location_rules_ = parse_mcdata_profile_rules(profile);
  } catch (const std::exception &error) {
    mcdata_location_rules_ = {};
    event("profile_warning", {{"reason", std::string("MCData location rules: ") + error.what()}});
  }
  mcdata_alias_locks_.clear();
  for (const auto &alias : mcdata_location_rules_.functional_aliases)
    if (alias.manual_deactivation_locked) mcdata_alias_locks_.insert(alias.alias);
  std::erase_if(mcdata_location_rules_.functional_aliases,
                [](const auto &alias) { return !alias.has_location_criteria(); });
  mcdata_rule_area_inside_.clear();
  Json aliases = Json::array(), rules = Json::array();
  for (const auto &alias : mcdata_location_rules_.functional_aliases) aliases.push_back(alias.alias);
  for (const auto &rule : mcdata_location_rules_.affiliation_rules)
    rules.push_back({{"group", rule.group},
                     {"affiliate", rule.affiliate},
                     {"enter_areas", rule.location.enter.size()},
                     {"exit_areas", rule.location.exit.size()},
                     {"speed_or_heading_areas", rule.location.constrained},
                     {"aliases", rule.aliases}});
  status_["mcdata_location_rules"] = {
      {"functional_aliases", aliases},
      {"affiliation_rules", rules},
      {"manual_deaffiliation_locked", mcdata_location_rules_.manual_deaffiliation_locked},
      {"manual_deactivation_locked", mcdata_alias_locks_}};
}

//------------------------------------------------------------------------------
// Helper: whether the position (lat, lon) crossed into (entry) or out of (!entry) one of `areas` since the last
// position; where the client now is, for each area, is kept under `key`. With no last position nothing is crossed.
bool Client::mcdata_rule_area_crossed(const std::string &key, const std::vector<location::Area> &areas, bool entry,
                                      double lat, double lon) {
  bool crossed = false;
  for (std::size_t index = 0; index < areas.size(); ++index) {
    const auto slot = key + "|" + std::to_string(index);
    const bool inside = location::inside(areas[index], lat, lon);
    const auto previous = mcdata_rule_area_inside_.find(slot);
    if (previous != mcdata_rule_area_inside_.end() && previous->second != inside && inside == entry) crossed = true;
    mcdata_rule_area_inside_[slot] = inside;
  }
  return crossed;
}

//------------------------------------------------------------------------------
// The position changed: TS 24.282 clauses 22.2.1.4 and 8.2.6.2 in turn.
void Client::mcdata_location_rules(const location::Point &position) {
  mcdata_last_position_ = position;
  const double lat = position.lat(), lon = position.lon();
  // TS 24.282 clause 22.2.1.4: "If a location criterion for functional alias activation or de-activation is met, the
  // MCData client shall initiate the functional alias status change procedure as specified in clause 22.2.1.2". A
  // criterion is the entry into or the exit from an area (TS 24.484 clause 10.3.2.1, <entry> item 5) b), c):
  // <EnterSpecificArea>, <ExitSpecificArea>); an alias already in the state asked for is left as it is.
  for (const auto &rule : mcdata_location_rules_.functional_aliases) {
    const auto key = "fa|" + rule.alias;
    const bool activate = mcdata_rule_area_crossed(key + "|activate-enter", rule.activate_on_entry, true, lat, lon) |
                          mcdata_rule_area_crossed(key + "|activate-exit", rule.activate_on_exit, false, lat, lon);
    const bool deactivate =
        mcdata_rule_area_crossed(key + "|deactivate-enter", rule.deactivate_on_entry, true, lat, lon) |
        mcdata_rule_area_crossed(key + "|deactivate-exit", rule.deactivate_on_exit, false, lat, lon);
    const bool active =
        (mcdata_alias_publish_open_.empty() ? mcdata_aliases_standing_ : mcdata_aliases_wanted_).contains(rule.alias);
    const char *action = activate && !deactivate && !active  ? "activate"
                         : deactivate && !activate && active ? "deactivate"
                                                             : nullptr;
    if (!action || !mcdata_authorized_) continue;
    event("mcdata_functional_alias_location", {{"alias", rule.alias}, {"action", action}, {"position", {lat, lon}}});
    try {
      mcdata_functional_alias(action, {rule.alias});
      if (activate)
        mcdata_aliases_by_location_.insert(rule.alias);
      else
        mcdata_aliases_by_location_.erase(rule.alias);
    } catch (const std::exception &error) {
      event("mcdata_functional_alias_location_failed", {{"alias", rule.alias}, {"reason", error.what()}});
    }
  }
  // TS 24.282 clause 8.2.6.2: "A rule is fulfilled if any of the location criteria and any of the functional alias
  // based criteria are met": a rule whose location criterion the position meets (an area entered or left) is
  // fulfilled when its functional alias criterion is met too (one of its aliases active; none listed, met).
  std::map<std::string, std::pair<bool, bool>> fulfilled; // group -> (affiliate, de-affiliate)
  for (std::size_t index = 0; index < mcdata_location_rules_.affiliation_rules.size(); ++index) {
    const auto &rule = mcdata_location_rules_.affiliation_rules[index];
    const auto key = "rule|" + std::to_string(index);
    const bool crossed = mcdata_rule_area_crossed(key + "|enter", rule.location.enter, true, lat, lon) |
                         mcdata_rule_area_crossed(key + "|exit", rule.location.exit, false, lat, lon);
    if (crossed && mcdata_rule_aliases_met(rule))
      (rule.affiliate ? fulfilled[rule.group].first : fulfilled[rule.group].second) = true;
  }
  mcdata_apply_affiliation_rules(fulfilled, "location");
}

//------------------------------------------------------------------------------
// Helper: whether the functional alias criterion of a rule is met: no alias listed, or one of them active.
bool Client::mcdata_rule_aliases_met(const McpttAffiliationRule &rule) const {
  return rule.aliases.empty() || std::any_of(rule.aliases.begin(), rule.aliases.end(),
                                             [&](const auto &alias) { return mcdata_aliases_active_.contains(alias); });
}

//------------------------------------------------------------------------------
// TS 24.282 clause 8.2.6.2: "These rules are evaluated ... whenever a functional alias is activated or deactivated":
// a rule with an alias criterion one of whose aliases became active is fulfilled when its location criterion is met
// at the last position too (inside an area of <EnterSpecificArea>, outside one of <ExitSpecificArea>; a rule without
// location criteria, met). A rule whose location criteria all carry a speed or a heading is never met.
void Client::mcdata_rules_aliases_changed(const std::set<std::string> &before) {
  std::map<std::string, std::pair<bool, bool>> fulfilled;
  for (const auto &rule : mcdata_location_rules_.affiliation_rules) {
    if (rule.aliases.empty()) continue;
    const bool became_active = std::any_of(rule.aliases.begin(), rule.aliases.end(), [&](const auto &alias) {
      return mcdata_aliases_active_.contains(alias) && !before.contains(alias);
    });
    if (!became_active) continue;
    bool location_met = rule.location.empty() && rule.location.constrained == 0;
    if (!location_met && mcdata_last_position_) {
      const double lat = mcdata_last_position_->lat(), lon = mcdata_last_position_->lon();
      location_met = std::any_of(rule.location.enter.begin(), rule.location.enter.end(),
                                 [&](const auto &area) { return location::inside(area, lat, lon); }) ||
                     std::any_of(rule.location.exit.begin(), rule.location.exit.end(),
                                 [&](const auto &area) { return !location::inside(area, lat, lon); });
    }
    if (location_met) (rule.affiliate ? fulfilled[rule.group].first : fulfilled[rule.group].second) = true;
  }
  mcdata_apply_affiliation_rules(fulfilled, "functional alias");
}

//------------------------------------------------------------------------------
// Helper: TS 24.282 clause 8.2.6.2: "If any defined rule is fulfilled, the MCData client shall initiate the
// affiliation status change procedure as specified in clause 8.2.2": affiliate to the groups an affiliation rule
// fulfilled names, de-affiliate from those a de-affiliation rule names (both at once for one group: nothing).
void Client::mcdata_apply_affiliation_rules(const std::map<std::string, std::pair<bool, bool>> &fulfilled,
                                            const char *trigger) {
  if (!mcdata_authorized_) return;
  for (const auto &[group, wanted] : fulfilled) {
    const auto [join, leave] = wanted;
    if (join == leave) continue;
    event("mcdata_affiliation_rule_fulfilled", {{"group", group}, {"affiliate", join}, {"trigger", trigger}});
    try {
      if (join) {
        if (mcdata_change_affiliation({group}, {})) mcdata_rule_affiliated_groups_.insert(group);
      } else {
        mcdata_change_affiliation({}, {group});
        mcdata_rule_affiliated_groups_.erase(group);
      }
    } catch (const std::exception &error) {
      event("mcdata_affiliation_rule_failed", {{"group", group}, {"reason", error.what()}});
    }
  }
  status_["mcdata_rule_affiliated_groups"] = mcdata_rule_affiliated_groups_;
}

//------------------------------------------------------------------------------
// TS 24.282 clause 8.2.2: "If the affiliation to the group has been activated due to a rule being fulfilled and the
// <manual-deaffiliation-not-allowed-if-affiliation-rules-are-met> element is present and is set to a value of "true",
// the MCData client shall suppress the MCData user's request": the user is told (NOTE 0) by the command failing.
void Client::mcdata_check_manual_deaffiliation(const std::vector<std::string> &groups) const {
  for (const auto &candidate : groups)
    if (mcdata_rule_affiliated_groups_.contains(candidate) &&
        mcdata_location_rules_.manual_deaffiliation_locked.contains(candidate))
      throw std::runtime_error("de-affiliation from " + candidate +
                               " is not allowed while its affiliation rules are met");
}

//------------------------------------------------------------------------------
// TS 24.282 clause 22.2.1.2: "If the functional alias has been activated due to a location area trigger and the
// <manual-deactivation-not-allowed-if-location-criteria-met> element is set to a value of "true", the MCData client
// shall suppress the MCData user's request" to deactivate it: the user is told (NOTE 1) by the command failing.
void Client::mcdata_check_manual_alias_deactivation(const std::vector<std::string> &aliases) const {
  for (const auto &alias : aliases)
    if (mcdata_aliases_by_location_.contains(alias) && mcdata_alias_locks_.contains(alias))
      throw std::runtime_error("deactivation of " + alias + " is not allowed while its location criteria are met");
}

} // namespace mcx
