// SPDX-License-Identifier: MIT
// SPDX-FileCopyrightText: 2025 Huang Rui <vowstar@gmail.com>
package com.vowstar.uart

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

import java.lang.foreign.Arena

// Layer interface (empty for this module)
class UartTxLayers(parameter: UartParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

// Hardware IO interface
class UartTxIO(parameter: UartParameter) extends HWBundle(parameter):
  val clock = Flipped(Clock())
  val reset = Flipped(Reset())
  val uart_txd    = Aligned(Bool())
  val uart_tx_busy = Aligned(Bool())
  val uart_tx_en  = Flipped(Bool())
  val uart_tx_data = Flipped(UInt(parameter.payloadBits.W))

// Probe interface (empty)
class UartTxProbe(parameter: UartParameter)
    extends DVBundle[UartParameter, UartTxLayers](parameter)

// FSM states encoded as UInt values
object TxState:
  val IDLE:  Int = 0
  val START: Int = 1
  val SEND:  Int = 2
  val STOP:  Int = 3

@generator
object UartTxModule extends Generator[UartParameter, UartTxLayers, UartTxIO, UartTxProbe]:

  def architecture(parameter: UartParameter) =
    val io = summon[Interface[UartTxIO]]

    // Clock and reset for registers
    given Ref[Clock] = io.clock
    given Ref[Reset] = io.reset

    val cyclesPerBit = parameter.cyclesPerBit
    val payloadBits  = parameter.payloadBits
    val stopBits     = parameter.stopBits
    val countRegLen  = parameter.countRegLen

    // Internal registers
    val txdReg       = RegInit(true.B)
    val dataToSend   = RegInit(0.U(payloadBits.W))
    val cycleCounter = RegInit(0.U(countRegLen.W))
    val bitCounter   = RegInit(0.U(4.W))
    val fsmState     = RegInit(TxState.IDLE.U(2.W))

    // Output assignments
    io.uart_txd     := txdReg
    io.uart_tx_busy := fsmState =/= TxState.IDLE.U(2.W)

    // Condition signals
    val nextBit     = cycleCounter === cyclesPerBit.U(countRegLen.W)
    val payloadDone = bitCounter === payloadBits.U(4.W)
    val stopDone    = (bitCounter === stopBits.U(4.W)) & (fsmState === TxState.STOP.U(2.W))

    // FSM state transitions
    when(fsmState === TxState.IDLE.U(2.W)) {
      when(io.uart_tx_en) {
        fsmState := TxState.START.U(2.W)
      }
    }.otherwise {
      when(fsmState === TxState.START.U(2.W)) {
        when(nextBit) {
          fsmState := TxState.SEND.U(2.W)
        }
      }.otherwise {
        when(fsmState === TxState.SEND.U(2.W)) {
          when(payloadDone) {
            fsmState := TxState.STOP.U(2.W)
          }
        }.otherwise {
          when(fsmState === TxState.STOP.U(2.W)) {
            when(stopDone) {
              fsmState := TxState.IDLE.U(2.W)
            }
          }
        }
      }
    }

    // Data register update
    when(fsmState === TxState.IDLE.U(2.W)) {
      when(io.uart_tx_en) {
        dataToSend := io.uart_tx_data
      }
    }.otherwise {
      when((fsmState === TxState.SEND.U(2.W)) & nextBit) {
        dataToSend := dataToSend >> 1
      }
    }

    // Bit counter update
    when((fsmState === TxState.IDLE.U(2.W)) | (fsmState === TxState.START.U(2.W))) {
      bitCounter := 0.U(4.W)
    }.otherwise {
      when((fsmState === TxState.SEND.U(2.W)) & (fsmState =/= TxState.STOP.U(2.W)) & nextBit) {
        bitCounter := bitCounter + 1.U(4.W)
      }.otherwise {
        when((fsmState === TxState.SEND.U(2.W)) & payloadDone) {
          bitCounter := 0.U(4.W)
        }.otherwise {
          when((fsmState === TxState.STOP.U(2.W)) & nextBit) {
            bitCounter := bitCounter + 1.U(4.W)
          }
        }
      }
    }

    // Cycle counter update
    when(nextBit) {
      cycleCounter := 0.U(countRegLen.W)
    }.otherwise {
      when((fsmState === TxState.START.U(2.W)) |
           (fsmState === TxState.SEND.U(2.W)) |
           (fsmState === TxState.STOP.U(2.W))) {
        cycleCounter := cycleCounter + 1.U(countRegLen.W)
      }
    }

    // TXD output register update
    when(fsmState === TxState.IDLE.U(2.W)) {
      txdReg := true.B
    }.otherwise {
      when(fsmState === TxState.START.U(2.W)) {
        txdReg := false.B
      }.otherwise {
        when(fsmState === TxState.SEND.U(2.W)) {
          txdReg := dataToSend.asBits.bit(0)
        }.otherwise {
          when(fsmState === TxState.STOP.U(2.W)) {
            txdReg := true.B
          }
        }
      }
    }
