// SPDX-License-Identifier: MIT
// SPDX-FileCopyrightText: 2025 Huang Rui <vowstar@gmail.com>
package com.vowstar.uart

import me.jiuyang.zaozi.*

// UART configuration parameters
case class UartParameter(
  bitRate:     Int = 9600,      // Baud rate in bits/second
  clkHz:       Int = 50000000,  // Clock frequency in Hz
  payloadBits: Int = 8,         // Data bits per frame
  stopBits:    Int = 1          // Stop bits per frame
) extends Parameter:
  require(bitRate > 0, "bitRate must be positive")
  require(clkHz > 0, "clkHz must be positive")
  require(payloadBits > 0 && payloadBits <= 16, "payloadBits must be 1-16")
  require(stopBits > 0 && stopBits <= 2, "stopBits must be 1 or 2")

  // Derived parameters
  val cyclesPerBit: Int = clkHz / bitRate
  val countRegLen:  Int = log2Ceil(cyclesPerBit) + 1

  private def log2Ceil(x: Int): Int =
    if (x <= 1) 1 else (Math.log(x) / Math.log(2)).ceil.toInt

given upickle.default.ReadWriter[UartParameter] = upickle.default.macroRW
