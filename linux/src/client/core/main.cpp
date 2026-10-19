//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

// mcx-client: the process entry point of the OpenMCX MC client (MCX Admin activation, the authorization request of a
// browser sign-in, control commands, run).
//
// Specifications: none cited (the procedures are in the Client member files).

#include "client.hpp"

//------------------------------------------------------------------------------
// Helper: parse the arguments, activate from MCX Admin once when asked, run a control command, print an authorization
// request or else run the client.
int main(int argc, char **argv) {
  try {
    auto args = mcx::arguments(argc, argv);
    // MCX Admin activation: once, then the activated configuration is used.
    if (!args.activate.empty()) {
      if (args.password_file.empty()) throw std::invalid_argument("--activate needs --password-file");
      if (mcx::activate_file(args.config, args.activate, args.password_file))
        mcx::event("activated", {{"config", args.config}});
    }
    auto cfg = mcx::load_config(args.config, args.disable_tls);
    if (mcx::dispatch_control(args, cfg)) return 0;
    // A sign-in in the user's browser (the dispatcher): the authorization request for the browser, whose code the
    // client then exchanges (oauth.authorization).
    if (args.authorization_request) {
      mcx::HttpClient http(cfg.at("tls").at("ca_file").get<std::string>(), {}, mcx::address_map(cfg),
                           mcx::HttpTrust::configured_and_system);
      std::cout << mcx::oidc_authorization_request(http, mcx::oauth_settings(cfg)).dump() << '\n';
      return 0;
    }
    mcx::print_banner("client");
    if (args.disable_tls) mcx::event("debug_clear_sip", {{"transport", "tcp"}, {"tls", false}});
    std::signal(SIGINT, mcx::stop_signal);
    std::signal(SIGTERM, mcx::stop_signal);
    mcx::Client client(std::move(cfg), args.disable_tls);
    client.run();
    return 0;
  } catch (const std::exception &error) {
    std::cerr << "mcx-client: " << error.what() << '\n';
    return 1;
  }
}
