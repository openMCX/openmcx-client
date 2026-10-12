//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

// mcx-client: the process entry point of the OpenMCX MC client (MCX Admin activation, control commands, run).
//
// Specifications: none cited (the procedures are in the Client member files).

#include "client.hpp"

//------------------------------------------------------------------------------
// Helper: parse the arguments, activate from MCX Admin once when asked, run a control command or else the client.
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
