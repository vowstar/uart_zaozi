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
  val clock = Flipped(Clock())
  val reset = Flipped(Reset())
  val uart_txd     = Aligned(Bool())
  val uart_tx_busy = Aligned(Bool())
  val uart_tx_en   = Flipped(Bool())
  val uart_tx_data = Flipped(UInt(parameter.payloadBits.W))
  val uart_rxd      = Flipped(Bool())
  val uart_rx_en    = Flipped(Bool())
  val uart_rx_break = Aligned(Bool())
  val uart_rx_valid = Aligned(Bool())
  val uart_rx_data  = Aligned(UInt(parameter.payloadBits.W))

// Probe interface (empty)
class UartProbe(parameter: UartParameter)
    extends DVBundle[UartParameter, UartLayers](parameter)

// FSM states
object UartTxState:
  val IDLE:  Int = 0
  val START: Int = 1
  val SEND:  Int = 2
  val STOP:  Int = 3

object UartRxState:
  val IDLE:  Int = 0
  val START: Int = 1
  val RECV:  Int = 2
  val STOP:  Int = 3

@generator
object UartModule extends Generator[UartParameter, UartLayers, UartIO, UartProbe]:

  override def moduleName(parameter: UartParameter): String = "Uart"

  def architecture(parameter: UartParameter) =
    val io = summon[Interface[UartIO]]

    given Ref[Clock] = io.clock
    given Ref[Reset] = io.reset

    val cyclesPerBit     = parameter.cyclesPerBit
    val halfCyclesPerBit = cyclesPerBit / 2
    val payloadBits      = parameter.payloadBits
    val stopBits         = parameter.stopBits
    val countRegLen      = parameter.countRegLen

    // ========== TX Logic ==========
    val txdReg         = RegInit(true.B)
    val dataToSend     = RegInit(0.U(payloadBits.W))
    val txCycleCounter = RegInit(0.U(countRegLen.W))
    val txBitCounter   = RegInit(0.U(4.W))
    val txFsmState     = RegInit(UartTxState.IDLE.U(2.W))

    // TX state decode
    val txStateIdle  = Wire(Bool())
    val txStateStart = Wire(Bool())
    val txStateSend  = Wire(Bool())
    val txStateStop  = Wire(Bool())
    txStateIdle  := txFsmState === UartTxState.IDLE.U(2.W)
    txStateStart := txFsmState === UartTxState.START.U(2.W)
    txStateSend  := txFsmState === UartTxState.SEND.U(2.W)
    txStateStop  := txFsmState === UartTxState.STOP.U(2.W)

    // TX control signals
    val txNextBit       = Wire(Bool())
    val txPayloadDone   = Wire(Bool())
    val txStopDone      = Wire(Bool())
    val txActive        = Wire(Bool())
    val txSendNextBit   = Wire(Bool())
    val txBitCounterInc = Wire(UInt(4.W))
    val txCycleCounterInc = Wire(UInt(countRegLen.W))

    txNextBit       := txCycleCounter === cyclesPerBit.U(countRegLen.W)
    txPayloadDone   := txBitCounter === payloadBits.U(4.W)
    txStopDone      := txStateStop & (txBitCounter === stopBits.U(4.W))
    txActive        := txStateStart | txStateSend | txStateStop
    txSendNextBit   := txStateSend & txNextBit
    txBitCounterInc := (txBitCounter + 1.U(4.W)).asBits.bits(3, 0).asUInt

    // Derived control signals
    val txSendShift = Wire(Bool())
    txSendShift := txSendNextBit & !txPayloadDone
    txCycleCounterInc := (txCycleCounter + 1.U(countRegLen.W)).asBits.bits(countRegLen - 1, 0).asUInt

    // TX outputs
    io.uart_txd     := txdReg
    io.uart_tx_busy := !txStateIdle

    // TX FSM
    when(txStateIdle) {
      when(io.uart_tx_en) {
        txFsmState := UartTxState.START.U(2.W)
      }
    }.otherwise {
      when(txStateStart & txNextBit) {
        txFsmState := UartTxState.SEND.U(2.W)
      }.otherwise {
        when(txStateSend & txPayloadDone) {
          txFsmState := UartTxState.STOP.U(2.W)
        }.otherwise {
          when(txStopDone) {
            txFsmState := UartTxState.IDLE.U(2.W)
          }
        }
      }
    }

    // TX data register
    when(txStateIdle & io.uart_tx_en) {
      dataToSend := io.uart_tx_data
    }.otherwise {
      when(txSendNextBit) {
        dataToSend := dataToSend >> 1
      }
    }

    // TX bit counter
    when(txStateIdle | txStateStart) {
      txBitCounter := 0.U(4.W)
    }.otherwise {
      when(txSendShift) {
        txBitCounter := txBitCounterInc
      }.otherwise {
        when(txStateSend & txPayloadDone) {
          txBitCounter := 0.U(4.W)
        }.otherwise {
          when(txStateStop & txNextBit) {
            txBitCounter := txBitCounterInc
          }
        }
      }
    }

    // TX cycle counter
    when(txNextBit) {
      txCycleCounter := 0.U(countRegLen.W)
    }.otherwise {
      when(txActive) {
        txCycleCounter := txCycleCounterInc
      }
    }

    // TX output register
    when(txStateIdle) {
      txdReg := true.B
    }.otherwise {
      when(txStateStart) {
        txdReg := false.B
      }.otherwise {
        when(txStateSend) {
          txdReg := dataToSend.asBits.bit(0)
        }.otherwise {
          when(txStateStop) {
            txdReg := true.B
          }
        }
      }
    }

    // ========== RX Logic ==========
    val rxdReg0        = RegInit(true.B)
    val rxdReg         = RegInit(true.B)
    val receivedData   = RegInit(0.U(payloadBits.W))
    val uartRxData     = RegInit(0.U(payloadBits.W))
    val rxCycleCounter = RegInit(0.U(countRegLen.W))
    val rxBitCounter   = RegInit(0.U(4.W))
    val bitSample      = RegInit(false.B)
    val rxFsmState     = RegInit(UartRxState.IDLE.U(2.W))
    val rxNFsmState    = Wire(UInt(2.W))
    val rxValidReg     = RegInit(false.B)

    // RX state decode
    val rxStateIdle  = Wire(Bool())
    val rxStateStart = Wire(Bool())
    val rxStateRecv  = Wire(Bool())
    val rxStateStop  = Wire(Bool())
    rxStateIdle  := rxFsmState === UartRxState.IDLE.U(2.W)
    rxStateStart := rxFsmState === UartRxState.START.U(2.W)
    rxStateRecv  := rxFsmState === UartRxState.RECV.U(2.W)
    rxStateStop  := rxFsmState === UartRxState.STOP.U(2.W)

    // RX control signals
    val rxHalfBit       = Wire(Bool())
    val rxFullBit       = Wire(Bool())
    val rxNextBit       = Wire(Bool())
    val rxPayloadDone   = Wire(Bool())
    val rxActive        = Wire(Bool())
    val rxStartEdge     = Wire(Bool())
    val rxRecvNextBit   = Wire(Bool())
    val rxDone          = Wire(Bool())
    val rxShiftedData   = Wire(UInt(payloadBits.W))
    val rxBitCounterInc = Wire(UInt(4.W))
    val rxCycleCounterInc = Wire(UInt(countRegLen.W))

    rxHalfBit       := rxCycleCounter === halfCyclesPerBit.U(countRegLen.W)
    rxFullBit       := rxCycleCounter === cyclesPerBit.U(countRegLen.W)
    rxNextBit       := rxFullBit | (rxStateStop & rxHalfBit)
    rxPayloadDone   := rxBitCounter === payloadBits.U(4.W)
    rxActive        := rxStateStart | rxStateRecv | rxStateStop
    rxStartEdge     := rxStateIdle & !rxdReg
    rxRecvNextBit   := rxStateRecv & rxNextBit
    rxShiftedData   := (bitSample.asBits ## receivedData.asBits.bits(payloadBits - 1, 1)).asUInt
    rxBitCounterInc := (rxBitCounter + 1.U(4.W)).asBits.bits(3, 0).asUInt
    rxCycleCounterInc := (rxCycleCounter + 1.U(countRegLen.W)).asBits.bits(countRegLen - 1, 0).asUInt

    // Input sync
    when(io.uart_rx_en) {
      rxdReg0 := io.uart_rxd
      rxdReg  := rxdReg0
    }

    // RX FSM next state
    rxNFsmState := rxFsmState
    when(rxStateIdle) {
      rxNFsmState := rxStartEdge.?(UartRxState.START.U(2.W), UartRxState.IDLE.U(2.W))
    }.otherwise {
      when(rxStateStart) {
        rxNFsmState := rxNextBit.?(UartRxState.RECV.U(2.W), UartRxState.START.U(2.W))
      }.otherwise {
        when(rxStateRecv) {
          rxNFsmState := rxPayloadDone.?(UartRxState.STOP.U(2.W), UartRxState.RECV.U(2.W))
        }.otherwise {
          when(rxStateStop) {
            rxNFsmState := rxNextBit.?(UartRxState.IDLE.U(2.W), UartRxState.STOP.U(2.W))
          }
        }
      }
    }

    rxFsmState := rxNFsmState

    // RX valid pulse
    rxDone := rxStateStop & (rxNFsmState === UartRxState.IDLE.U(2.W))
    rxValidReg := rxDone

    // RX outputs
    io.uart_rx_valid := rxValidReg
    io.uart_rx_break := rxValidReg & (uartRxData === 0.U(payloadBits.W))
    io.uart_rx_data  := uartRxData

    when(rxStateStop) {
      uartRxData := receivedData
    }

    // RX data shift
    when(rxStateIdle) {
      receivedData := 0.U(payloadBits.W)
    }.otherwise {
      when(rxRecvNextBit) {
        receivedData := rxShiftedData
      }
    }

    // RX bit counter
    when(!rxStateRecv) {
      rxBitCounter := 0.U(4.W)
    }.otherwise {
      when(rxRecvNextBit) {
        rxBitCounter := rxBitCounterInc
      }
    }

    // RX bit sample at mid-bit
    when(rxHalfBit) {
      bitSample := rxdReg
    }

    // RX cycle counter
    when(rxNextBit) {
      rxCycleCounter := 0.U(countRegLen.W)
    }.otherwise {
      when(rxActive) {
        rxCycleCounter := rxCycleCounterInc
      }
    }
