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
        in {
          devShells.default = pkgs.mkShell {
            packages = [ jdk pkgs.gradle_8 pkgs.git pkgs.curl ];
            shellHook = ''
              export JAVA_HOME=${jdk}
            '';
          };
        });
    in
      perSystem // nixosOutputs;
}
