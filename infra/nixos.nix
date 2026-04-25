{ config, pkgs, lib, modulesPath, ... }:

let
  cfg = config.services.btone;
in
{
  imports = [
    # Amazon EC2 profile: GRUB on /dev/xvda, ENA, growpart, etc.
    "${modulesPath}/virtualisation/amazon-image.nix"
  ];

  options.services.btone = {
    serverHost = lib.mkOption {
      type = lib.types.str;
      default = "centerbeam.proxy.rlwy.net";
      description = "Minecraft server hostname the bot auto-joins on launch.";
    };

    serverPort = lib.mkOption {
      type = lib.types.port;
      default = 40387;
      description = "Minecraft server port.";
    };

    username = lib.mkOption {
      type = lib.types.str;
      default = "BotEC2";
      description = ''
        Offline-mode in-game username. Must be unique on the server —
        if the laptop-side bot is already connected as "Bot", the cloud
        deploy needs its own name (default "BotEC2") or the server will
        refuse the duplicate connection.
      '';
    };

    modJarPath = lib.mkOption {
      type = lib.types.path;
      default = "/var/lib/btone/mods/btone-mod-c-0.1.0.jar";
      description = ''
        Where the btone-mod-c jar must live before btone-bot.service can
        start. The skill's `bin/btone-ec2.sh push` step `scp`s it here.
      '';
    };

    mcVersion = lib.mkOption {
      type = lib.types.str;
      default = "1.21.8";
    };

    fabricLoader = lib.mkOption {
      type = lib.types.str;
      default = "0.19.2";
    };

    xvfbDisplay = lib.mkOption {
      type = lib.types.str;
      default = ":99";
      description = "Virtual framebuffer DISPLAY value the bot inherits.";
    };

    xvfbResolution = lib.mkOption {
      type = lib.types.str;
      default = "1280x720x24";
      description = "Xvfb -screen 0 argument (WxHxDepth).";
    };
  };

  config = {
    ec2.hvm = true;

    nix.settings.experimental-features = [ "nix-command" "flakes" ];

    networking.hostName = "btone-ec2";
    networking.firewall.allowedTCPPorts = [ 22 ];

    time.timeZone = "UTC";

    services.openssh = {
      enable = true;
      settings = {
        PermitRootLogin = "prohibit-password";
        PasswordAuthentication = false;
      };
    };

    # Mesa + llvmpipe — software OpenGL for the rendered MC client.
    hardware.graphics.enable = true;

    environment.systemPackages = with pkgs; [
      temurin-bin-21
      jq
      curl
      git
      python3
      portablemc
      xorg.xorgserver
      xorg.xrandr
      mesa
      mesa-demos  # glxinfo, useful for debugging GL setup over SSH
    ];

    environment.sessionVariables = {
      JAVA_HOME = "${pkgs.temurin-bin-21}";
    };

    users.users.btone = {
      isSystemUser = true;
      group = "btone";
      home = "/var/lib/btone";
      createHome = true;
      description = "btone-mod-c Minecraft bot service account";
    };
    users.groups.btone = { };

    systemd.tmpfiles.rules = [
      "d /var/lib/btone        0755 btone btone -"
      "d /var/lib/btone/mods   0755 btone btone -"
      "d /var/lib/btone/config 0755 btone btone -"
    ];

    # Virtual framebuffer. Plain Xvfb — no window manager, no compositor.
    systemd.services.xvfb = {
      description = "Xvfb virtual framebuffer for the btone bot";
      wantedBy = [ "multi-user.target" ];
      serviceConfig = {
        ExecStart = "${pkgs.xorg.xorgserver}/bin/Xvfb ${cfg.xvfbDisplay} -screen 0 ${cfg.xvfbResolution} -nolisten tcp";
        Restart = "always";
        RestartSec = "5s";
        User = "btone";
        Group = "btone";
      };
    };

    # Fetch the non-btone mods (fabric-api, kotlin, meteor, baritone, sodium)
    # once on first boot. setup-portablemc.sh embeds the same logic; this is a
    # one-shot Nix-side equivalent so the SKILL only has to scp the btone jar.
    systemd.services.btone-mods-bootstrap = {
      description = "Download Fabric/Meteor/Baritone/Sodium mods if missing";
      wantedBy = [ "btone-bot.service" ];
      before = [ "btone-bot.service" ];
      after = [ "network-online.target" ];
      requires = [ "network-online.target" ];
      path = with pkgs; [ curl jq coreutils ];
      serviceConfig = {
        Type = "oneshot";
        RemainAfterExit = true;
        User = "btone";
        Group = "btone";
      };
      script = ''
        set -euo pipefail
        MC=${cfg.mcVersion}
        MODS=/var/lib/btone/mods
        STAMP=$MODS/.bootstrap-done
        [ -f "$STAMP" ] && exit 0

        fetch_modrinth() {
          local slug="$1"
          local url
          url=$(curl -sfL "https://api.modrinth.com/v2/project/$slug/version?game_versions=%5B%22$MC%22%5D&loaders=%5B%22fabric%22%5D" \
            | jq -r '.[0].files[] | select(.primary == true) | .url' | head -n1)
          [ -n "$url" ] && [ "$url" != "null" ] || { echo "no $MC fabric release for $slug"; exit 1; }
          curl -sfL -o "$MODS/$(basename "$url")" "$url"
        }

        fetch_modrinth fabric-api
        fetch_modrinth fabric-language-kotlin
        # Sodium is intentionally OMITTED for the EC2 deploy. On a real
        # GPU it reduces render-thread saturation, but under Mesa
        # llvmpipe its advanced shader path segfaults in
        # lp_rast_shade_tile (libgallium). Vanilla MC's renderer is
        # slower but stable on software rasterizers.

        curl -sfL -o "$MODS/meteor-client-$MC.jar" \
          "https://meteorclient.com/api/download?version=$MC"
        curl -sfL -o "$MODS/baritone-api-fabric-1.15.0.jar" \
          "https://github.com/cabaletta/baritone/releases/download/v1.15.0/baritone-api-fabric-1.15.0.jar"

        touch "$STAMP"
      '';
    };

    # The bot itself. Refuses to start until the mod jar is in place
    # (the SKILL has the agent scp it after the first nixos-rebuild).
    systemd.services.btone-bot = {
      description = "btone-mod-c Minecraft bot (headless via Xvfb + llvmpipe)";
      after = [ "xvfb.service" "btone-mods-bootstrap.service" "network-online.target" ];
      requires = [ "xvfb.service" ];
      wants = [ "btone-mods-bootstrap.service" "network-online.target" ];
      wantedBy = [ "multi-user.target" ];

      environment = {
        DISPLAY = cfg.xvfbDisplay;
        LIBGL_ALWAYS_SOFTWARE = "1";
        # LWJGL3 / MC require GL 3.3 core. llvmpipe supports it but doesn't
        # always advertise it without this override.
        MESA_GL_VERSION_OVERRIDE = "3.3";
        MESA_GLSL_VERSION_OVERRIDE = "330";
        # llvmpipe JIT-compiles shaders and segfaults in lp_rast_shade_tile
        # under MC's first frame on Mesa 26. softpipe interprets GLSL
        # without JIT — much slower but stable. Override at startup.
        GALLIUM_DRIVER = "softpipe";
        JAVA_HOME = "${pkgs.temurin-bin-21}";
      };

      serviceConfig = {
        User = "btone";
        Group = "btone";
        WorkingDirectory = "/var/lib/btone";
        # Block the bot from launching with no mod jar — clearer failure than
        # MC silently starting vanilla and the agent getting RPC timeouts.
        ExecStartPre = "${pkgs.coreutils}/bin/test -f ${cfg.modJarPath}";
        ExecStart = lib.concatStringsSep " " [
          "${pkgs.portablemc}/bin/portablemc"
          "--work-dir /var/lib/btone"
          "start fabric:${cfg.mcVersion}:${cfg.fabricLoader}"
          "--jvm ${pkgs.temurin-bin-21}/bin/java"
          "--auth-anonymize"
          "-u ${cfg.username}"
          "-s ${cfg.serverHost}"
          "-p ${toString cfg.serverPort}"
        ];
        Restart = "on-failure";
        RestartSec = "15s";
        StandardOutput = "journal";
        StandardError = "journal";
      };
    };

    # NixOS root user gets the operator's SSH key from EC2 metadata
    # (KeyName parameter on the CloudFormation stack). Nothing else needed —
    # amazon-image.nix wires up cloud-init for that.

    system.stateVersion = "24.11";
  };
}
