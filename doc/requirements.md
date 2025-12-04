# UART Requirements Model

## 1. Protocol Specification

UART 8N1 (8 data bits, No parity, 1 stop bit)

### 1.1 Frame Format

```bash
IDLE (HIGH) -> START (LOW, 1 bit) -> DATA (8 bits, LSB first) -> STOP (HIGH, 1 bit) -> IDLE
```

### 1.2 Timing

- Bit period: `T_bit = 1 / baud_rate`
- Cycles per bit: `cycles_per_bit = clk_hz / baud_rate`
- RX sampling: at mid-bit (cycles_per_bit / 2)

## 2. Parameters

|  Parameter  | Type | Default  |       Description        |
| ----------- | ---- | -------- | ------------------------ |
| bitRate     | Int  | 9600     | Baud rate in bits/second |
| clkHz       | Int  | 50000000 | Clock frequency in Hz    |
| payloadBits | Int  | 8        | Data bits per frame      |
| stopBits    | Int  | 1        | Stop bits per frame      |

Derived: `cyclesPerBit = clkHz / bitRate`

## 3. TX State Machine

```bash
        +------+
        | IDLE         | <---------+ |
        | +------+     |             |
        |              |             |
        | tx_en        | stop_done   |
        | v            |             |
        | +-------+    |             |
        | START        |             |
        | +-------+    |             |
        |              |             |
        | next_bit     |             |
        | v            |             |
        | +------+     |             |
        | SEND         | ----+       |
        | +------+     |             |
        | ^            |             |
        | +-------+    |             |
        | payload_done |             |
        |              |             |
        | v            |             |
        | +------+     |             |
        | STOP         | ----------+ |
        +------+
```

### 3.1 TX Signals

|    Signal    | Dir | Width |    Description     |
| ------------ | --- | ----- | ------------------ |
| clk          | in  | 1     | Clock              |
| resetn       | in  | 1     | Active-low reset   |
| uart_txd     | out | 1     | TX data line       |
| uart_tx_busy | out | 1     | TX busy flag       |
| uart_tx_en   | in  | 1     | Start transmission |
| uart_tx_data | in  | 8     | Data to transmit   |

### 3.2 TX Behavior

- IDLE: txd=1, wait for tx_en
- START: txd=0, count cycles_per_bit
- SEND: txd=data[bit_counter], shift LSB first
- STOP: txd=1, count cycles_per_bit * stop_bits

## 4. RX State Machine

```bash
        +------+
        | IDLE         | <---------+        |
        | +------+     |                    |
        |              |                    |
        | rxd=0        | next_bit (in STOP) |
        | v            |                    |
        | +-------+    |                    |
        | START        |                    |
        | +-------+    |                    |
        |              |                    |
        | next_bit     |                    |
        | v            |                    |
        | +------+     |                    |
        | RECV         | ----+              |
        | +------+     |                    |
        | ^            |                    |
        | +-------+    |                    |
        | payload_done |                    |
        |              |                    |
        | v            |                    |
        | +------+     |                    |
        | STOP         | ----------+        |
        +------+
```

### 4.1 RX Signals

|    Signal     | Dir | Width |   Description    |
| ------------- | --- | ----- | ---------------- |
| clk           | in  | 1     | Clock            |
| resetn        | in  | 1     | Active-low reset |
| uart_rxd      | in  | 1     | RX data line     |
| uart_rx_en    | in  | 1     | Receive enable   |
| uart_rx_valid | out | 1     | Valid data pulse |
| uart_rx_break | out | 1     | Break condition  |
| uart_rx_data  | out | 8     | Received data    |

### 4.2 RX Behavior

- IDLE: wait for rxd falling edge
- START: wait half bit, then full bit to center
- RECV: sample at mid-bit, shift into register
- STOP: validate stop bit, output valid pulse

## 5. Properties to Verify

### 5.1 Safety Properties

1. TX never glitches during transmission
2. TX frame timing is exactly cycles_per_bit per bit
3. RX samples at mid-bit
4. No data corruption in loopback

### 5.2 Liveness Properties

1. TX eventually completes after tx_en
2. RX eventually outputs valid after complete frame

## 6. Verification Method

- cocotb simulation with UartSource/UartSink
- Loopback test: TX -> RX with data comparison
- Timing verification: check bit periods
