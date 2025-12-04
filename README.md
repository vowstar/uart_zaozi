# UART Zaozi

UART implementation using [zaozi](https://github.com/sequencer/zaozi) EDSL.

## Features

- Full-duplex UART (TX + RX)
- Configurable baud rate, payload bits, stop bits
- Break condition detection
- Default: 115200 baud, 8N1, 50MHz clock

## Prerequisites

- [Nix](https://nixos.org/download.html) with flakes enabled
- Clone [zaozi](https://github.com/sequencer/zaozi) to `../zaozi`

## Usage

```bash
# Enter development shell (auto-builds zaozi if needed)
nix develop

# Generate Verilog
build-uart

# Run tests
cd test/test_uart && make
```

## Project Structure

```bash
uart_zaozi/
├── uart/src/
│   ├── Uart.scala         # UART module (TX + RX)
│   └── UartParameter.scala # Configuration parameters
├── test/test_uart/
│   ├── test_uart.py       # cocotb testbench
│   └── test_uart.v        # Verilog wrapper
├── result/                # Generated Verilog (after build)
└── flake.nix              # Nix build configuration
```

## Configuration

Edit `flake.nix` to change parameters:

```nix
uartConfig = {
  bitRate = 115200;
  clkHz = 50000000;
  payloadBits = 8;
  stopBits = 1;
};
```

## License

MIT
