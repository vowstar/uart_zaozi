// SPDX-License-Identifier: MIT
// SPDX-FileCopyrightText: 2025 Huang Rui <vowstar@gmail.com>
//
// Test wrapper for UART module
// This wraps the zaozi-generated UART for cocotb testing

`timescale 1ns/1ps

module test_uart (
    input  wire       clk,
    input  wire       reset,

    // TX interface
    output wire       uart_txd,
    output wire       uart_tx_busy,
    input  wire       uart_tx_en,
    input  wire [7:0] uart_tx_data,

    // RX interface
    input  wire       uart_rxd,
    input  wire       uart_rx_en,
    output wire       uart_rx_break,
    output wire       uart_rx_valid,
    output wire [7:0] uart_rx_data
);

// Instantiate the zaozi-generated UART module
UartModule_e0f01525 u_uart (
    .clock        (clk),
    .reset        (reset),
    .uart_txd     (uart_txd),
    .uart_tx_busy (uart_tx_busy),
    .uart_tx_en   (uart_tx_en),
    .uart_tx_data (uart_tx_data),
    .uart_rxd     (uart_rxd),
    .uart_rx_en   (uart_rx_en),
    .uart_rx_break(uart_rx_break),
    .uart_rx_valid(uart_rx_valid),
    .uart_rx_data (uart_rx_data)
);

endmodule
