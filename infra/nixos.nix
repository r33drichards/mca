{ config, pkgs, lib, modulesPath, ... }:

let
  cfg = config.services.btone;

  # Build the mod jar on the instance. Fabric Loom needs network access,
  # so this derivation opts out of the sandbox via `__noChroot = true` —
  # which only works when nix.settings.sandbox = "relaxed" (set below).
  btone-mod-c = pkgs.stdenv.mkDerivation {
    pname = "btone-mod-c";
    version = "0.1.0";
    src = ../mod-c;

    __noChroot = true;

    nativeBuildInputs = [ pkgs.temurin-bin-21 pkgs.gradle_8 ];

    JAVA_HOME = "${pkgs.temurin-bin-21}";

    buildPhase = ''
      runHook preBuild
      export GRADLE_USER_HOME=$TMPDIR/gradle-home
      gradle \
        --no-daemon \
        --no-watch-fs \
        --console=plain \
        --gradle-user-home "$GRADLE_USER_HOME" \
        build
      runHook postBuild
    '';

    installPhase = ''
      runHook preInstall
      mkdir -p $out
      cp build/libs/btone-mod-c-0.1.0.jar $out/
      runHook postInstall
    '';
  };
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
      default = "${btone-mod-c}/btone-mod-c-0.1.0.jar";
      description = ''
        Path to the btone-mod-c jar. By default it's built from
        `../mod-c` as a Nix derivation during nixos-rebuild — no scp
        needed. Override only if you want to substitute a hand-built
        jar.
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

    display = lib.mkOption {
      type = lib.types.str;
      default = ":99";
      description = "DISPLAY value the bot inherits.";
    };

    resolution = lib.mkOption {
      type = lib.types.str;
      default = "1280x720";
      description = "Virtual display resolution.";
    };
  };

  config = {
    ec2.hvm = true;

    nix.settings = {
      experimental-features = [ "nix-command" "flakes" ];
      # Allow `__noChroot = true` derivations (used by btone-mod-c
      # because Fabric Loom needs network for mappings/MC jars).
      sandbox = "relaxed";
      # cuda-maintainers prebuilds the proprietary nvidia driver against
      # the standard nixpkgs kernels, so we don't have to compile a
      # ~500MB kernel module on every fresh deploy.
      extra-substituters = [
        "https://cuda-maintainers.cachix.org"
        "https://nix-community.cachix.org"
      ];
      extra-trusted-public-keys = [
        "cuda-maintainers.cachix.org-1:0dq3bujKpuEPMCX6U4WylrUDZ9JyUG0VpVZa7CNfq5E="
        "nix-community.cachix.org-1:mB9FSh9qf2dCimDSUo8Zy7bkq5CX+/rkCWyvRCYg3Fs="
      ];
    };

    # The proprietary Nvidia driver is non-free; allow it explicitly.
    nixpkgs.config.allowUnfree = true;

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

    # Nvidia driver — proprietary closed-source. The Tesla T4 in g4dn.xlarge
    # works with the production branch.
    hardware.graphics.enable = true;
    hardware.nvidia = {
      modesetting.enable = true;
      open = false;
      nvidiaSettings = false;
      powerManagement.enable = false;
      package = config.boot.kernelPackages.nvidiaPackages.production;
    };

    # services.xserver here is only used for its side-effect of pulling in
    # the Nvidia X module + libGL etc. — we don't run the standard X session
    # (no display manager, no autorun); xorg-headless.service below starts
    # Xorg the way we want it.
    services.xserver = {
      enable = true;
      autorun = false;
      videoDrivers = [ "nvidia" ];
      displayManager.startx.enable = true;  # disables LightDM/GDM
    };

    environment.systemPackages = with pkgs; [
      temurin-bin-21
      jq
      curl
      git
      python3
      portablemc
      xorg.xorgserver
      xorg.xrandr
      mesa-demos          # glxinfo, useful for debugging GL setup over SSH
      pciutils            # lspci — Sodium probes for GPU info via this
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

    # Headless Xorg config. AllowEmptyInitialConfiguration makes the
    # Nvidia driver bring up Xorg with no monitor connected, and the
    # virtual ModeLine gives the bot a real framebuffer to render into.
    environment.etc."X11/xorg-headless.conf".text = ''
      Section "ServerLayout"
        Identifier "Layout0"
        Screen 0 "Screen0" 0 0
      EndSection

      Section "Device"
        Identifier "Device0"
        Driver     "nvidia"
        VendorName "NVIDIA Corporation"
        Option     "AllowEmptyInitialConfiguration" "true"
      EndSection

      Section "Monitor"
        Identifier "Monitor0"
        HorizSync   28.0 - 80.0
        VertRefresh 48.0 - 75.0
        ModeLine    "${cfg.resolution}" 74.48 1280 1336 1472 1664 720 721 724 746 -HSync +Vsync
        Option      "DPMS"
      EndSection

      Section "Screen"
        Identifier "Screen0"
        Device     "Device0"
        Monitor    "Monitor0"
        DefaultDepth 24
        SubSection "Display"
          Depth 24
          Modes "${cfg.resolution}"
        EndSubSection
      EndSection
    '';

    # Make sure the nvidia kernel modules load on every boot. NixOS's
    # hardware.nvidia handles this for the standard graphical session;
    # since we run our own Xorg we make it explicit.
    boot.kernelModules = [ "nvidia" "nvidia_modeset" "nvidia_uvm" "nvidia_drm" ];

    # amazon-image.nix sets `nomodeset` + `vga=0x317` in kernelParams to
    # use a simple text console — fine for non-GPU EC2, but it disables
    # kernel mode-setting which Nvidia DRM requires. Filter them out.
    boot.kernelParams = lib.mkForce (
      builtins.filter
        (p: !(lib.hasPrefix "vga=" p) && p != "nomodeset")
        (lib.lists.unique (
          # Reproduce the rest of amazon-image.nix's defaults:
          [ "console=ttyS0,115200n8" "random.trust_cpu=on" "nvme_core.io_timeout=4294967295" ]
        ))
    );

    # Headless Xorg backed by the Nvidia GPU. Must run as root (the
    # nvidia X module needs CAP_SYS_ADMIN to talk to the GPU).
    systemd.services.xorg-headless = {
      description = "Headless Xorg server (Nvidia GPU) for the btone bot";
      wantedBy = [ "multi-user.target" ];
      after = [ "systemd-modules-load.service" "systemd-udev-settle.service" "network-online.target" ];
      serviceConfig = {
        Type = "simple";
        User = "root";
        # The nvidia X driver (nvidia_drv.so) lives in the nvidia package's
        # `bin` output. Without explicitly adding that path, Xorg's default
        # ModulePath sees only xorg-server's own modules and fails with
        # "couldn't open module nvidia".
        ExecStart = lib.concatStringsSep " " [
          "${pkgs.xorg.xorgserver}/bin/Xorg"
          cfg.display
          "-config /etc/X11/xorg-headless.conf"
          "-modulepath ${config.hardware.nvidia.package.bin}/lib/xorg/modules,${pkgs.xorg.xorgserver}/lib/xorg/modules"
          "-nolisten tcp"
          "-noreset"
          "-logfile /var/log/xorg-headless.log"
        ];
        Restart = "always";
        RestartSec = "5s";
      };
    };

    # Fetch non-btone mods (fabric-api, kotlin, meteor, baritone)
    # once on first boot. setup-portablemc.sh embeds the same logic; this
    # is a one-shot Nix-side equivalent so the SKILL only scp's the btone jar.
    systemd.services.btone-mods-bootstrap = {
      description = "Download Fabric/Meteor/Baritone mods if missing";
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
        # Sodium intentionally OMITTED for the EC2 deploy: under Mesa
        # llvmpipe its shader path segfaulted in lp_rast_shade_tile.
        # We're now on a real Nvidia GPU, but the omission stayed —
        # vanilla renderer is plenty fast at 6 chunk view distance.

        curl -sfL -o "$MODS/meteor-client-$MC.jar" \
          "https://meteorclient.com/api/download?version=$MC"
        curl -sfL -o "$MODS/baritone-api-fabric-1.15.0.jar" \
          "https://github.com/cabaletta/baritone/releases/download/v1.15.0/baritone-api-fabric-1.15.0.jar"

        touch "$STAMP"
      '';
    };

    # The bot itself. Refuses to start until the mod jar is in place.
    systemd.services.btone-bot = {
      description = "btone-mod-c Minecraft bot (headless GPU-rendered)";
      after = [ "xorg-headless.service" "btone-mods-bootstrap.service" "network-online.target" ];
      requires = [ "xorg-headless.service" ];
      wants = [ "btone-mods-bootstrap.service" "network-online.target" ];
      wantedBy = [ "multi-user.target" ];

      environment = {
        DISPLAY = cfg.display;
        # Real GPU, real Nvidia libGL — no Mesa software fallback needed.
        # Explicitly set 0 in case anything inherits the previous deploy's
        # LIBGL_ALWAYS_SOFTWARE=1 from a stale /etc/profile.
        LIBGL_ALWAYS_SOFTWARE = "0";
        JAVA_HOME = "${pkgs.temurin-bin-21}";
      };

      serviceConfig = {
        User = "btone";
        Group = "btone";
        WorkingDirectory = "/var/lib/btone";
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

    system.stateVersion = "24.11";
  };
}
