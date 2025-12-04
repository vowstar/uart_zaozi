// SPDX-License-Identifier: MIT
// SPDX-FileCopyrightText: 2025 Huang Rui <vowstar@gmail.com>
package com.vowstar.uart

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

import java.lang.foreign.Arena

// Layer interface (empty for this module)
class UartLayers(parameter: UartParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

// Combined UART IO (TX + RX)
class UartIO(parameter: UartParameter) extends HWBundle(parameter):
  // Clock and reset (active-high reset)
  val clock = Flipped(Clock())
  val reset = Flipped(Reset())

  // TX interface
  val uart_txd     = Aligned(Bool())
  val uart_tx_busy = Aligned(Bool())
  val uart_tx_en   = Flipped(Bool())
  val uart_tx_data = Flipped(UInt(parameter.payloadBits.W))

  // RX interface
  val uart_rxd      = Flipped(Bool())
  val uart_rx_en    = Flipped(Bool())
  val uart_rx_break = Aligned(Bool())
  val uart_rx_valid = Aligned(Bool())
  val uart_rx_data  = Aligned(UInt(parameter.payloadBits.W))

// Probe interface (empty)
class UartProbe(parameter: UartParameter)
    extends DVBundle[UartParameter, UartLayers](parameter)

// FSM states for TX
object UartTxState:
  val IDLE:  Int = 0
  val START: Int = 1
  val SEND:  Int = 2
  val STOP:  Int = 3

// FSM states for RX
object UartRxState:
  val IDLE:  Int = 0
  val START: Int = 1
  val RECV:  Int = 2
  val STOP:  Int = 3

@generator
object UartModule extends Generator[UartParameter, UartLayers, UartIO, UartProbe]:

  def architecture(parameter: UartParameter) =
    val io = summon[Interface[UartIO]]

    given Ref[Clock] = io.clock
    given Ref[Reset] = io.reset

    val cyclesPerBit = parameter.cyclesPerBit
    val payloadBits  = parameter.payloadBits
    val stopBits     = parameter.stopBits
    val countRegLen  = parameter.countRegLen

    // ========== TX Logic ==========
    val txdReg       = RegInit(true.B)
    val dataToSend   = RegInit(0.U(payloadBits.W))
    val txCycleCounter = RegInit(0.U(countRegLen.W))
    val txBitCounter = RegInit(0.U(4.W))
    val txFsmState   = RegInit(UartTxState.IDLE.U(2.W))

    io.uart_txd     := txdReg
    io.uart_tx_busy := txFsmState =/= UartTxState.IDLE.U(2.W)

    val txNextBit     = txCycleCounter === cyclesPerBit.U(countRegLen.W)
    val txPayloadDone = txBitCounter === payloadBits.U(4.W)
    val txStopDone    = (txBitCounter === stopBits.U(4.W)) & (txFsmState === UartTxState.STOP.U(2.W))

    // TX FSM
    when(txFsmState === UartTxState.IDLE.U(2.W)) {
      when(io.uart_tx_en) {
        txFsmState := UartTxState.START.U(2.W)
      }
    }.otherwise {
      when(txFsmState === UartTxState.START.U(2.W)) {
        when(txNextBit) {
          txFsmState := UartTxState.SEND.U(2.W)
        }
      }.otherwise {
        when(txFsmState === UartTxState.SEND.U(2.W)) {
          when(txPayloadDone) {
            txFsmState := UartTxState.STOP.U(2.W)
          }
        }.otherwise {
          when(txFsmState === UartTxState.STOP.U(2.W)) {
            when(txStopDone) {
              txFsmState := UartTxState.IDLE.U(2.W)
            }
          }
        }
      }
    }

    // TX data register
    when(txFsmState === UartTxState.IDLE.U(2.W)) {
      when(io.uart_tx_en) {
        dataToSend := io.uart_tx_data
      }
    }.otherwise {
      when((txFsmState === UartTxState.SEND.U(2.W)) & txNextBit) {
        dataToSend := dataToSend >> 1
      }
    }

    // TX bit counter
    when((txFsmState === UartTxState.IDLE.U(2.W)) | (txFsmState === UartTxState.START.U(2.W))) {
      txBitCounter := 0.U(4.W)
    }.otherwise {
      when((txFsmState === UartTxState.SEND.U(2.W)) & txNextBit & !txPayloadDone) {
        txBitCounter := (txBitCounter + 1.U(4.W)).asBits.bits(3, 0).asUInt
      }.otherwise {
        when((txFsmState === UartTxState.SEND.U(2.W)) & txPayloadDone) {
          txBitCounter := 0.U(4.W)
        }.otherwise {
          when((txFsmState === UartTxState.STOP.U(2.W)) & txNextBit) {
            txBitCounter := (txBitCounter + 1.U(4.W)).asBits.bits(3, 0).asUInt
          }
        }
      }
    }

    // TX cycle counter
    when(txNextBit) {
      txCycleCounter := 0.U(countRegLen.W)
    }.otherwise {
      when((txFsmState === UartTxState.START.U(2.W)) |
           (txFsmState === UartTxState.SEND.U(2.W)) |
           (txFsmState === UartTxState.STOP.U(2.W))) {
        txCycleCounter := (txCycleCounter + 1.U(countRegLen.W)).asBits.bits(countRegLen - 1, 0).asUInt
      }
    }

    // TX output
    when(txFsmState === UartTxState.IDLE.U(2.W)) {
      txdReg := true.B
    }.otherwise {
      when(txFsmState === UartTxState.START.U(2.W)) {
        txdReg := false.B
      }.otherwise {
        when(txFsmState === UartTxState.SEND.U(2.W)) {
          txdReg := dataToSend.asBits.bit(0)
        }.otherwise {
          when(txFsmState === UartTxState.STOP.U(2.W)) {
            txdReg := true.B
          }
        }
      }
    }

    // ========== RX Logic ==========
    val rxdReg0      = RegInit(true.B)
    val rxdReg       = RegInit(true.B)
    val receivedData = RegInit(0.U(payloadBits.W))
    val uartRxData   = RegInit(0.U(payloadBits.W))
    val rxCycleCounter = RegInit(0.U(countRegLen.W))
    val rxBitCounter = RegInit(0.U(4.W))
    val bitSample    = RegInit(false.B)
    val rxFsmState   = RegInit(UartRxState.IDLE.U(2.W))
    val rxNFsmState  = Wire(UInt(2.W))
    val rxValidReg   = RegInit(false.B)

    // Input sync
    when(io.uart_rx_en) {
      rxdReg0 := io.uart_rxd
      rxdReg  := rxdReg0
    }

    // next_bit: full bit period, except STOP state exits at half bit
    val rxNextBit = (rxCycleCounter === cyclesPerBit.U(countRegLen.W)) |
                    ((rxFsmState === UartRxState.STOP.U(2.W)) &
                     (rxCycleCounter === (cyclesPerBit / 2).U(countRegLen.W)))
    val rxPayloadDone = rxBitCounter === payloadBits.U(4.W)

    // RX next state (matches uart_ref logic)
    rxNFsmState := rxFsmState
    when(rxFsmState === UartRxState.IDLE.U(2.W)) {
      rxNFsmState := (!rxdReg).?(UartRxState.START.U(2.W), UartRxState.IDLE.U(2.W))
    }.otherwise {
      when(rxFsmState === UartRxState.START.U(2.W)) {
        rxNFsmState := rxNextBit.?(UartRxState.RECV.U(2.W), UartRxState.START.U(2.W))
      }.otherwise {
        when(rxFsmState === UartRxState.RECV.U(2.W)) {
          rxNFsmState := rxPayloadDone.?(UartRxState.STOP.U(2.W), UartRxState.RECV.U(2.W))
        }.otherwise {
          when(rxFsmState === UartRxState.STOP.U(2.W)) {
            rxNFsmState := rxNextBit.?(UartRxState.IDLE.U(2.W), UartRxState.STOP.U(2.W))
          }
        }
      }
    }

    rxFsmState := rxNFsmState

    // RX valid: register the transition so cocotb can see it after clock edge
    // Valid pulses for one cycle when transitioning from STOP to IDLE
    rxValidReg := (rxFsmState === UartRxState.STOP.U(2.W)) & (rxNFsmState === UartRxState.IDLE.U(2.W))

    // RX outputs
    io.uart_rx_valid := rxValidReg
    io.uart_rx_break := rxValidReg & (uartRxData === 0.U(payloadBits.W))
    io.uart_rx_data  := uartRxData

    when(rxFsmState === UartRxState.STOP.U(2.W)) {
      uartRxData := receivedData
    }

    // RX data shift (matches uart_ref: shift when next_bit in RECV state)
    when(rxFsmState === UartRxState.IDLE.U(2.W)) {
      receivedData := 0.U(payloadBits.W)
    }.otherwise {
      when((rxFsmState === UartRxState.RECV.U(2.W)) & rxNextBit) {
        receivedData := (bitSample.asBits ## receivedData.asBits.bits(payloadBits - 1, 1)).asUInt
      }
    }

    // RX bit counter
    when(rxFsmState =/= UartRxState.RECV.U(2.W)) {
      rxBitCounter := 0.U(4.W)
    }.otherwise {
      when((rxFsmState === UartRxState.RECV.U(2.W)) & rxNextBit) {
        rxBitCounter := (rxBitCounter + 1.U(4.W)).asBits.bits(3, 0).asUInt
      }
    }

    // RX bit sample
    when(rxCycleCounter === (cyclesPerBit / 2).U(countRegLen.W)) {
      bitSample := rxdReg
    }

    // RX cycle counter (matches uart_ref: reset on next_bit)
    when(rxNextBit) {
      rxCycleCounter := 0.U(countRegLen.W)
    }.otherwise {
      when((rxFsmState === UartRxState.START.U(2.W)) |
           (rxFsmState === UartRxState.RECV.U(2.W)) |
           (rxFsmState === UartRxState.STOP.U(2.W))) {
        rxCycleCounter := (rxCycleCounter + 1.U(countRegLen.W)).asBits.bits(countRegLen - 1, 0).asUInt
      }
    }
