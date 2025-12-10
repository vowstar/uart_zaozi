# SPDX-License-Identifier: MIT
# SPDX-FileCopyrightText: 2025 Huang Rui <vowstar@gmail.com>
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

        # UART parameters
        uartConfig = {
          bitRate = 115200;
          clkHz = 50000000;
          payloadBits = 8;
          stopBits = 1;
        };

        # Use zaozi-assembly from upstream
        zaozi-jar = "${zaozi.packages.${system}.zaozi-assembly}/share/java/elaborator.jar";

        # Build script
        buildScript = pkgs.writeShellScriptBin "build-uart" ''
          set -e

          JAVA_LIBRARY_PATH="${pkgs.circt-install}/lib:${pkgs.mlir-install}/lib"
          OUTPUT_DIR="''${OUTPUT_DIR:-$PWD/result}"

          mkdir -p "$OUTPUT_DIR"

          echo "=== Building UART with zaozi ==="
          echo "OUTPUT_DIR: $OUTPUT_DIR"

          # Generate config
          scala-cli run \
            --server=false \
            --extra-jars "${zaozi-jar}" \
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
            --extra-jars "${zaozi-jar}" \
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
          MLIRBC_FILE=$(ls Uart*.mlirbc 2>/dev/null | head -1)

          if [ -z "$MLIRBC_FILE" ]; then
            echo "Error: No .mlirbc file generated"
            exit 1
          fi

          ${pkgs.circt-install}/bin/firtool "$MLIRBC_FILE" --split-verilog \
            -disable-all-randomization \
            -g \
            --emit-hgldd \
            --hgldd-output-dir="$OUTPUT_DIR" \
            --lowering-options=noAlwaysComb,disallowLocalVariables,disallowPackedArrays,emittedLineLength=160,verifLabels,explicitBitcast,locationInfoStyle=wrapInAtSquareBracket,wireSpillingHeuristic=spillLargeTermsWithNamehints,disallowMuxInlining,wireSpillingNamehintTermLimit=8,maximumNumberOfTermsPerExpression=8,disallowExpressionInliningInPorts,caseInsensitiveKeywords \
            -o "$OUTPUT_DIR"

          rm -f *.mlirbc

          echo "=== Verilog generated in: $OUTPUT_DIR ==="
          echo "=== Build complete ==="
        '';

      in
      {
        # Build UART Verilog
        packages.default = pkgs.runCommand "uart-verilog" {
          nativeBuildInputs = [ pkgs.scala-cli pkgs.circt-install pkgs.mlir-install ];
          JAVA_TOOL_OPTIONS = "--enable-preview";
        } ''
          mkdir -p $out
          cd ${./uart/src}

          JAVA_LIBRARY_PATH="${pkgs.circt-install}/lib:${pkgs.mlir-install}/lib"

          # Generate config
          scala-cli run \
            --server=false \
            --extra-jars "${zaozi-jar}" \
            --scala-version 3.6.2 \
            -O="-experimental" \
            --java-opt "--enable-native-access=ALL-UNNAMED" \
            --java-opt "--enable-preview" \
            --java-opt "-Djava.library.path=$JAVA_LIBRARY_PATH" \
            UartParameter.scala \
            Uart.scala \
            -- config "$out/uart_config.json" \
            --bitRate ${toString uartConfig.bitRate} \
            --clkHz ${toString uartConfig.clkHz} \
            --payloadBits ${toString uartConfig.payloadBits} \
            --stopBits ${toString uartConfig.stopBits}

          # Generate MLIR bytecode
          scala-cli run \
            --server=false \
            --extra-jars "${zaozi-jar}" \
            --scala-version 3.6.2 \
            -O="-experimental" \
            --java-opt "--enable-native-access=ALL-UNNAMED" \
            --java-opt "--enable-preview" \
            --java-opt "-Djava.library.path=$JAVA_LIBRARY_PATH" \
            UartParameter.scala \
            Uart.scala \
            -- design "$out/uart_config.json"

          MLIRBC_FILE=$(ls Uart*.mlirbc 2>/dev/null | head -1)
          ${pkgs.circt-install}/bin/firtool "$MLIRBC_FILE" --split-verilog \
            -disable-all-randomization \
            -g \
            --emit-hgldd \
            --hgldd-output-dir="$out" \
            --lowering-options=noAlwaysComb,disallowLocalVariables,disallowPackedArrays,emittedLineLength=160,verifLabels,explicitBitcast,locationInfoStyle=wrapInAtSquareBracket,wireSpillingHeuristic=spillLargeTermsWithNamehints,disallowMuxInlining,wireSpillingNamehintTermLimit=8,maximumNumberOfTermsPerExpression=8,disallowExpressionInliningInPorts,caseInsensitiveKeywords \
            -o "$out"
        '';

        # App for interactive build
        apps.default = {
          type = "app";
          program = "${buildScript}/bin/build-uart";
        };

        apps.build = {
          type = "app";
          program = "${buildScript}/bin/build-uart";
        };

        # Development shell
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

          env = {
            CIRCT_INSTALL_PATH = pkgs.circt-install;
            MLIR_INSTALL_PATH = pkgs.mlir-install;
            JEXTRACT_INSTALL_PATH = pkgs.jextract-21;
            JAVA_TOOL_OPTIONS = "--enable-preview -Djextract.decls.per.header=65535";
            ZAOZI_JAR = zaozi-jar;
          };

          shellHook = ''
            echo "========================================"
            echo "UART Zaozi Development Environment"
            echo "========================================"
            echo "ZAOZI_JAR: $ZAOZI_JAR"
            echo ""
            echo "Build Verilog:  build-uart"
            echo "Run tests:      cd test/test_uart && make"
            echo "========================================"
          '';
        };
      }
    );
}
