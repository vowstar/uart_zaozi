# SPDX-License-Identifier: MIT
# SPDX-FileCopyrightText: 2025 Huang Rui <vowstar@gmail.com>

DUT = test_uart
DUT_DIR = $(abspath $(CURDIR))
TOPLEVEL = test_uart
MODULE = test_uart

# Test wrapper
VERILOG_TEST_SOURCES = $(DUT_DIR)/$(DUT).v

# RTL directory (generated Verilog from zaozi via build-uart)
VERILOG_DIR = $(DUT_DIR)/../../result

# RTL sources
VERILOG_SOURCES = $(VERILOG_TEST_SOURCES)
VERILOG_SOURCES += $(wildcard $(VERILOG_DIR)/*.v)

# Python path for cocotbext modules
PYTHONPATH := $(DUT_DIR)/../:$(PYTHONPATH)
export PYTHONPATH

include ../make/Makefile.mk
