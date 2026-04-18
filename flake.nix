{
  description = "btone dev shell: JDK 21 + Gradle for Fabric mod development";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachSystem [
      "x86_64-darwin"
      "aarch64-darwin"
      "x86_64-linux"
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
}
