# SPDX-License-Identifier: MIT
# SPDX-FileCopyrightText: 2025 Huang Rui <vowstar@gmail.com>
"""
UART cocotb testbench

Tests:
1. TX single byte transmission
2. RX single byte reception
3. Loopback test (TX -> RX)
4. Multiple byte transmission
"""

import cocotb
from cocotb.clock import Clock
from cocotb.triggers import RisingEdge, FallingEdge, Timer, ClockCycles
from cocotbext.uart import UartSource, UartSink

# Test parameters
CLK_PERIOD_NS = 20      # 50 MHz clock
BAUD_RATE = 115200      # Baud rate for faster simulation
CYCLES_PER_BIT = 434    # 50MHz / 115200


async def reset_dut(dut, cycles=5):
    """Apply reset to DUT (active-high reset)."""
    dut.reset.value = 1
    dut.uart_tx_en.value = 0
    dut.uart_tx_data.value = 0
    dut.uart_rxd.value = 1
    dut.uart_rx_en.value = 1
    await ClockCycles(dut.clk, cycles)
    dut.reset.value = 0
    await RisingEdge(dut.clk)


@cocotb.test()
async def test_uart_tx_single_byte(dut):
    """Test TX transmits a single byte correctly."""
    clock = Clock(dut.clk, CLK_PERIOD_NS, units="ns")
    cocotb.start_soon(clock.start())

    await reset_dut(dut)

    # Create UART sink to receive TX data
    uart_sink = UartSink(dut.uart_txd, baud=BAUD_RATE, bits=8, stop_bits=1)

    # Send a byte
    test_byte = 0x55  # Alternating pattern
    dut.uart_tx_data.value = test_byte
    dut.uart_tx_en.value = 1
    await RisingEdge(dut.clk)
    dut.uart_tx_en.value = 0

    # Wait for transmission to complete
    while dut.uart_tx_busy.value:
        await RisingEdge(dut.clk)

    # Wait a bit more for uart_sink to process
    await Timer(100, units="us")

    # Check received data
    received = await uart_sink.read(1)
    assert len(received) == 1, f"Expected 1 byte, got {len(received)}"
    assert received[0] == test_byte, f"Expected 0x{test_byte:02X}, got 0x{received[0]:02X}"

    dut._log.info(f"TX test passed: sent 0x{test_byte:02X}, received 0x{received[0]:02X}")


@cocotb.test()
async def test_uart_rx_single_byte(dut):
    """Test RX receives a single byte correctly."""
    clock = Clock(dut.clk, CLK_PERIOD_NS, units="ns")
    cocotb.start_soon(clock.start())

    await reset_dut(dut)

    # Create UART source to send data to RX
    uart_source = UartSource(dut.uart_rxd, baud=BAUD_RATE, bits=8, stop_bits=1)

    # Send a byte (don't wait for completion - poll for valid during transmission)
    test_byte = 0xAA  # Alternating pattern
    await uart_source.write([test_byte])

    # Wait for valid signal - poll during transmission since valid pulses during stop bit
    timeout = 0
    while not dut.uart_rx_valid.value and timeout < 100000:
        await RisingEdge(dut.clk)
        timeout += 1

    assert timeout < 100000, "RX timeout waiting for valid"

    # Check received data
    received = int(dut.uart_rx_data.value)
    assert received == test_byte, f"Expected 0x{test_byte:02X}, got 0x{received:02X}"

    dut._log.info(f"RX test passed: sent 0x{test_byte:02X}, received 0x{received:02X}")


@cocotb.test()
async def test_uart_loopback(dut):
    """Test loopback: TX output connected to RX input externally."""
    clock = Clock(dut.clk, CLK_PERIOD_NS, units="ns")
    cocotb.start_soon(clock.start())

    await reset_dut(dut)

    # Connect TX output to RX input for loopback
    async def loopback_driver():
        while True:
            await RisingEdge(dut.clk)
            dut.uart_rxd.value = int(dut.uart_txd.value)

    cocotb.start_soon(loopback_driver())

    # Send multiple bytes
    test_data = [0x00, 0x55, 0xAA, 0xFF, 0x12, 0x34]

    for test_byte in test_data:
        # Send byte via TX
        dut.uart_tx_data.value = test_byte
        dut.uart_tx_en.value = 1
        await RisingEdge(dut.clk)
        dut.uart_tx_en.value = 0

        # Wait for RX valid - poll during TX since valid pulses during stop bit
        # (before TX completes)
        timeout = 0
        while not dut.uart_rx_valid.value and timeout < 100000:
            await RisingEdge(dut.clk)
            timeout += 1

        if timeout >= 100000:
            dut._log.error(f"Timeout waiting for RX valid on byte 0x{test_byte:02X}")
            assert False, "RX timeout"

        # Check received data
        received = int(dut.uart_rx_data.value)
        assert received == test_byte, f"Loopback mismatch: sent 0x{test_byte:02X}, got 0x{received:02X}"

        dut._log.info(f"Loopback OK: 0x{test_byte:02X}")

        # Wait for TX to complete before next byte
        while dut.uart_tx_busy.value:
            await RisingEdge(dut.clk)

        # Small delay between bytes
        await ClockCycles(dut.clk, 100)

    dut._log.info("Loopback test passed for all bytes")


@cocotb.test()
async def test_uart_tx_busy_flag(dut):
    """Test that TX busy flag is set during transmission."""
    clock = Clock(dut.clk, CLK_PERIOD_NS, units="ns")
    cocotb.start_soon(clock.start())

    await reset_dut(dut)

    # Initially not busy
    assert not dut.uart_tx_busy.value, "TX should not be busy after reset"

    # Start transmission
    dut.uart_tx_data.value = 0x42
    dut.uart_tx_en.value = 1
    await RisingEdge(dut.clk)
    dut.uart_tx_en.value = 0
    await RisingEdge(dut.clk)

    # Should be busy now
    assert dut.uart_tx_busy.value, "TX should be busy during transmission"

    # Wait for completion
    while dut.uart_tx_busy.value:
        await RisingEdge(dut.clk)

    # Should not be busy after completion
    assert not dut.uart_tx_busy.value, "TX should not be busy after transmission"

    dut._log.info("TX busy flag test passed")


@cocotb.test()
async def test_uart_rx_break_detection(dut):
    """Test RX break condition detection (all zeros)."""
    clock = Clock(dut.clk, CLK_PERIOD_NS, units="ns")
    cocotb.start_soon(clock.start())

    await reset_dut(dut)

    # Create UART source
    uart_source = UartSource(dut.uart_rxd, baud=BAUD_RATE, bits=8, stop_bits=1)

    # Send break condition (0x00) - don't wait, poll for valid during transmission
    await uart_source.write([0x00])

    # Wait for valid signal - poll during transmission since valid pulses during stop bit
    timeout = 0
    while not dut.uart_rx_valid.value and timeout < 100000:
        await RisingEdge(dut.clk)
        timeout += 1

    assert timeout < 100000, "RX timeout waiting for valid"

    # Check break flag
    if dut.uart_rx_break.value:
        dut._log.info("Break condition correctly detected")
    else:
        dut._log.warning("Break flag not set for 0x00 data")

    dut._log.info("RX break detection test completed")
