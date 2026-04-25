{
  description = "btone dev shell + headless EC2 NixOS deployment";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    let
      # NixOS system for the headless EC2 deployment. Matches the
      # CloudFormation template in infra/btone-ec2.yaml.
      nixosOutputs = {
        nixosConfigurations.btone-ec2 = nixpkgs.lib.nixosSystem {
          system = "x86_64-linux";
          modules = [ ./infra/nixos.nix ];
        };
      };

      perSystem = flake-utils.lib.eachSystem [
        "x86_64-darwin"
        "aarch64-darwin"
        "x86_64-linux"
        "aarch64-linux"
      ] (system:
        let
          pkgs = import nixpkgs { inherit system; };
          jdk = pkgs.temurin-bin-21;

          # Fabric mod build. Loom decompiles MC and downloads mappings,
          # so the build needs network. We mark the derivation
          # `__noChroot = true` (escape the sandbox) — works only on a
          # nix daemon configured with `sandbox = relaxed`. The EC2
          # NixOS module sets that. Local laptop builds may need
          # `nix.conf: sandbox = relaxed` too.
          btone-mod-c = pkgs.stdenv.mkDerivation {
            pname = "btone-mod-c";
            version = "0.1.0";
            src = ./mod-c;

            __noChroot = true;

            nativeBuildInputs = [ jdk pkgs.gradle_8 ];

            JAVA_HOME = "${jdk}";

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
        in {
          devShells.default = pkgs.mkShell {
            packages = [ jdk pkgs.gradle_8 pkgs.git pkgs.curl ];
            shellHook = ''
              export JAVA_HOME=${jdk}
            '';
          };

          packages = {
            inherit btone-mod-c;
            default = btone-mod-c;
          };
        });
    in
      perSystem // nixosOutputs;
}
