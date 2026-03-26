{
  description = "A clj-nix flake";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
    garden-cli.url = "github:nextjournal/garden-cli";
  };

  outputs = { self, nixpkgs, flake-utils, garden-cli }:

    flake-utils.lib.eachDefaultSystem (system:

      let
        pkgs = import nixpkgs {
          inherit system;
          config.allowUnfree = true;
        };

        runtimeJDK = pkgs.jdk25;

        runtimeDeps = with pkgs; [ ];

      in {
        formatter = nixpkgs.legacyPackages.x86_64-linux.nixfmt;

        devShell = pkgs.mkShell {
          buildInputs = [
            (pkgs.clojure.override { jdk = runtimeJDK; })
            runtimeJDK
            pkgs.maven
            pkgs.zprint

            pkgs.babashka
            pkgs.clj-kondo
            pkgs.clojure-lsp

            pkgs.nodejs
            pkgs.nodePackages.npm
            pkgs.mprocs
            pkgs.ripgrep
            garden-cli.packages.${system}.default
          ] ++ runtimeDeps;
        };

      });
}

