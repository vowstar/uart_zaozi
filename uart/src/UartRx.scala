// SPDX-License-Identifier: MIT
// SPDX-FileCopyrightText: 2025 Huang Rui <vowstar@gmail.com>
package com.vowstar.uart

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

import java.lang.foreign.Arena

// Layer interface (empty for this module)
class UartRxLayers(parameter: UartParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

// Hardware IO interface
class UartRxIO(parameter: UartParameter) extends HWBundle(parameter):
  val clock = Flipped(Clock())
  val reset = Flipped(Reset())
  val uart_rxd      = Flipped(Bool())
  val uart_rx_en    = Flipped(Bool())
  val uart_rx_break = Aligned(Bool())
  val uart_rx_valid = Aligned(Bool())
  val uart_rx_data  = Aligned(UInt(parameter.payloadBits.W))

// Probe interface (empty)
class UartRxProbe(parameter: UartParameter)
    extends DVBundle[UartParameter, UartRxLayers](parameter)

// FSM states
object RxState:
  val IDLE:  Int = 0
  val START: Int = 1
  val RECV:  Int = 2
  val STOP:  Int = 3

@generator
object UartRxModule extends Generator[UartParameter, UartRxLayers, UartRxIO, UartRxProbe]:

  def architecture(parameter: UartParameter) =
    val io = summon[Interface[UartRxIO]]

    // Clock and reset for registers
    given Ref[Clock] = io.clock
    given Ref[Reset] = io.reset

    val cyclesPerBit = parameter.cyclesPerBit
    val payloadBits  = parameter.payloadBits
    val countRegLen  = parameter.countRegLen

    // Input synchronizer registers (2-stage)
    val rxdReg0 = RegInit(true.B)
    val rxdReg  = RegInit(true.B)

    // Data registers
    val receivedData = RegInit(0.U(payloadBits.W))
    val uartRxData   = RegInit(0.U(payloadBits.W))

    // Counter registers
    val cycleCounter = RegInit(0.U(countRegLen.W))
    val bitCounter   = RegInit(0.U(4.W))

    // Sampling register
    val bitSample = RegInit(false.B)

    // FSM state registers
    val fsmState  = RegInit(RxState.IDLE.U(2.W))
    val nFsmState = Wire(UInt(2.W))

    // Input synchronization (when rx enabled)
    when(io.uart_rx_en) {
      rxdReg0 := io.uart_rxd
      rxdReg  := rxdReg0
    }

    // Condition signals
    val nextBit = (cycleCounter === cyclesPerBit.U(countRegLen.W)) |
                  ((fsmState === RxState.STOP.U(2.W)) &
                   (cycleCounter === (cyclesPerBit / 2).U(countRegLen.W)))
    val payloadDone = bitCounter === payloadBits.U(4.W)

    // Next state logic (combinational)
    nFsmState := fsmState
    when(fsmState === RxState.IDLE.U(2.W)) {
      nFsmState := (!rxdReg).?(RxState.START.U(2.W), RxState.IDLE.U(2.W))
    }.otherwise {
      when(fsmState === RxState.START.U(2.W)) {
        nFsmState := nextBit.?(RxState.RECV.U(2.W), RxState.START.U(2.W))
      }.otherwise {
        when(fsmState === RxState.RECV.U(2.W)) {
          nFsmState := payloadDone.?(RxState.STOP.U(2.W), RxState.RECV.U(2.W))
        }.otherwise {
          when(fsmState === RxState.STOP.U(2.W)) {
            nFsmState := nextBit.?(RxState.IDLE.U(2.W), RxState.STOP.U(2.W))
          }
        }
      }
    }

    // FSM state register update
    fsmState := nFsmState

    // Output assignments
    io.uart_rx_valid := (fsmState === RxState.STOP.U(2.W)) & (nFsmState === RxState.IDLE.U(2.W))
    io.uart_rx_break := io.uart_rx_valid & (receivedData === 0.U(payloadBits.W))
    io.uart_rx_data  := uartRxData

    // Output data register update
    when(fsmState === RxState.STOP.U(2.W)) {
      uartRxData := receivedData
    }

    // Received data shift register
    when(fsmState === RxState.IDLE.U(2.W)) {
      receivedData := 0.U(payloadBits.W)
    }.otherwise {
      when((fsmState === RxState.RECV.U(2.W)) & nextBit) {
        // Shift right and insert sample at MSB
        receivedData := (bitSample.asBits ## receivedData.asBits.bits(payloadBits - 1, 1)).asUInt
      }
    }

    // Bit counter update
    when(fsmState =/= RxState.RECV.U(2.W)) {
      bitCounter := 0.U(4.W)
    }.otherwise {
      when((fsmState === RxState.RECV.U(2.W)) & nextBit) {
        bitCounter := bitCounter + 1.U(4.W)
      }
    }

    // Bit sample at mid-bit
    when(cycleCounter === (cyclesPerBit / 2).U(countRegLen.W)) {
      bitSample := rxdReg
    }

    // Cycle counter update
    when(nextBit) {
      cycleCounter := 0.U(countRegLen.W)
    }.otherwise {
      when((fsmState === RxState.START.U(2.W)) |
           (fsmState === RxState.RECV.U(2.W)) |
           (fsmState === RxState.STOP.U(2.W))) {
        cycleCounter := cycleCounter + 1.U(countRegLen.W)
      }
    }
