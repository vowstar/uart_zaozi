# SPDX-License-Identifier: MIT
# SPDX-FileCopyrightText: 2025 Huang Rui <vowstar@gmail.com>
#
# NOTE: Upstream zaozi nix packaging issue
# ----------------------------------------
# The zaozi project's nix package (pkgs.zaozi.zaozi-assembly) cannot be built
# due to a missing Mill dependency in their lock file:
#
#   Resolution failed for 1 modules:
#   com.lihaoyi:mill-libs-javalib-jarjarabrams-worker_3:1.0.0
#
# This prevents us from using a pure `nix build` approach. As a workaround,
# we automatically build zaozi from source in the shellHook when entering
# `nix develop`. Once upstream fixes this issue, we can simplify this flake
# to use `pkgs.zaozi.zaozi-assembly` directly.
#
# Upstream: https://github.com/sequencer/zaozi
#
{
  description = "UART implementation using zaozi EDSL";

  inputs = {
    zaozi.url = "github:sequencer/zaozi";
    nixpkgs.follows = "zaozi/nixpkgs";
    flake-utils.follows = "zaozi/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils, zaozi }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = zaozi.legacyPackages.${system};

        # UART parameters (can be overridden)
        uartConfig = {
          bitRate = 115200;
          clkHz = 50000000;
          payloadBits = 8;
          stopBits = 1;
        };

        # Build script that runs in the devShell environment
        buildScript = pkgs.writeShellScriptBin "build-uart" ''
          set -e

          # Check environment
          if [ -z "$CIRCT_INSTALL_PATH" ]; then
            echo "Error: CIRCT_INSTALL_PATH not set."
            echo "Please run this script inside 'nix develop' shell."
            exit 1
          fi

          # Find zaozi jar
          ZAOZI_JAR="''${ZAOZI_JAR:-}"

          # Try to find zaozi jar in common locations
          if [ -z "$ZAOZI_JAR" ] || [ ! -f "$ZAOZI_JAR" ]; then
            for path in \
              "../zaozi/out/zaozi/assembly.dest/out.jar" \
              "../../zaozi/out/zaozi/assembly.dest/out.jar" \
              "$HOME/.cache/zaozi/zaozi-assembly.jar"
            do
              if [ -f "$path" ]; then
                ZAOZI_JAR="$path"
                break
              fi
            done
          fi

          if [ -z "$ZAOZI_JAR" ] || [ ! -f "$ZAOZI_JAR" ]; then
            echo "Error: Zaozi jar not found."
            echo "Please build zaozi first:"
            echo "  cd ../zaozi && mill zaozi.assembly"
            echo "Or set ZAOZI_JAR environment variable."
            exit 1
          fi

          JAVA_LIBRARY_PATH="$CIRCT_INSTALL_PATH/lib:$MLIR_INSTALL_PATH/lib"
          OUTPUT_DIR="''${OUTPUT_DIR:-$PWD/result}"

          mkdir -p "$OUTPUT_DIR"

          echo "=== Building UART with zaozi ==="
          echo "ZAOZI_JAR: $ZAOZI_JAR"
          echo "OUTPUT_DIR: $OUTPUT_DIR"

          # Generate config
          scala-cli run \
            --server=false \
            --extra-jars "$ZAOZI_JAR" \
            --scala-version 3.6.2 \
            -O="-experimental" \
            --java-opt "--enable-native-access=ALL-UNNAMED" \
            --java-opt "--enable-preview" \
            --java-opt "-Djava.library.path=$JAVA_LIBRARY_PATH" \
            uart/src/UartParameter.scala \
            uart/src/Uart.scala \
            -- config "$OUTPUT_DIR/uart_config.json" \
            --bitRate ${toString uartConfig.bitRate} \
            --clkHz ${toString uartConfig.clkHz} \
            --payloadBits ${toString uartConfig.payloadBits} \
            --stopBits ${toString uartConfig.stopBits}

          echo "=== Config generated ==="

          # Generate MLIR bytecode
          scala-cli run \
            --server=false \
            --extra-jars "$ZAOZI_JAR" \
            --scala-version 3.6.2 \
            -O="-experimental" \
            --java-opt "--enable-native-access=ALL-UNNAMED" \
            --java-opt "--enable-preview" \
            --java-opt "-Djava.library.path=$JAVA_LIBRARY_PATH" \
            uart/src/UartParameter.scala \
            uart/src/Uart.scala \
            -- design "$OUTPUT_DIR/uart_config.json"

          echo "=== MLIR bytecode generated ==="

          # Convert MLIR to Verilog
          FIRTOOL="$CIRCT_INSTALL_PATH/bin/firtool"
          MLIRBC_FILE=$(ls UartModule*.mlirbc 2>/dev/null | head -1)

          if [ -z "$MLIRBC_FILE" ]; then
            echo "Error: No .mlirbc file generated"
            exit 1
          fi

          # Use lowering options to generate Icarus-compatible Verilog
          # disallowLocalVariables: avoid 'automatic' variables (not supported by Icarus)
          # disallowPackedArrays: use unpacked arrays for better compatibility
          "$FIRTOOL" "$MLIRBC_FILE" --verilog \
            --lowering-options=disallowLocalVariables,disallowPackedArrays \
            -o "$OUTPUT_DIR/Uart.v"

          # Cleanup
          rm -f *.mlirbc

          echo "=== Verilog generated: $OUTPUT_DIR/Uart.v ==="
          echo "=== Build complete ==="
        '';

      in
      {
        # App to build UART (requires nix develop environment)
        apps.default = {
          type = "app";
          program = "${buildScript}/bin/build-uart";
        };

        apps.build = {
          type = "app";
          program = "${buildScript}/bin/build-uart";
        };

        # Development shell with all tools
        devShells.default = pkgs.mkShell {
          buildInputs = [
            buildScript
            pkgs.scala-cli
            pkgs.circt-install
            pkgs.mlir-install
            pkgs.jextract-21
            pkgs.mill
            pkgs.verilog
            pkgs.verilator
            (pkgs.python3.withPackages (ps: with ps; [
              cocotb
              pytest
            ]))
          ];

          # Set environment variables directly (same as zaozi devShell)
          env = {
            CIRCT_INSTALL_PATH = pkgs.circt-install;
            MLIR_INSTALL_PATH = pkgs.mlir-install;
            JEXTRACT_INSTALL_PATH = pkgs.jextract-21;
            JAVA_TOOL_OPTIONS = "--enable-preview -Djextract.decls.per.header=65535";
          };

          shellHook = ''
            echo "========================================"
            echo "UART Zaozi Development Environment"
            echo "========================================"

            # Auto-build zaozi jar if not found
            ZAOZI_JAR=""
            for path in \
              "../zaozi/out/zaozi/assembly.dest/out.jar" \
              "../../zaozi/out/zaozi/assembly.dest/out.jar" \
              "$HOME/.cache/zaozi/zaozi-assembly.jar"
            do
              if [ -f "$path" ]; then
                ZAOZI_JAR="$path"
                break
              fi
            done

            if [ -z "$ZAOZI_JAR" ]; then
              echo ""
              echo "Zaozi jar not found. Building automatically..."
              echo ""
              if [ -d "../zaozi" ]; then
                pushd ../zaozi > /dev/null
                mill zaozi.assembly
                popd > /dev/null
                echo ""
                echo "Zaozi build complete."
              else
                echo "Error: ../zaozi directory not found."
                echo "Please clone zaozi repository:"
                echo "  git clone https://github.com/sequencer/zaozi ../zaozi"
              fi
            else
              echo "Zaozi jar found: $ZAOZI_JAR"
            fi

            echo ""
            echo "Build Verilog:  build-uart"
            echo "Run tests:      cd test/test_uart && make"
            echo "========================================"
          '';
        };
      }
    );
}
