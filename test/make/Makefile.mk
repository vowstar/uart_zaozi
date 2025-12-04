TOPLEVEL_LANG = verilog

# Smart simulator detection - check user preference first, then auto-detect
ifndef SIM
ifeq ($(shell command -v vcs >/dev/null 2>&1 && echo yes),yes)
SIM := vcs
else ifeq ($(shell command -v xrun >/dev/null 2>&1 && echo yes),yes)
SIM := xcelium
else ifeq ($(shell command -v iverilog >/dev/null 2>&1 && echo yes),yes)
SIM := icarus
$(warning WARNING: Using Icarus Verilog. For better simulation accuracy, please use VCS or Xcelium.)
else
$(error No supported simulator found. Please install VCS, Xcelium, or Icarus and ensure they are in PATH)
endif
endif

WAVES ?= 1

ifeq ($(strip $(DUT)),)
$(error DUT is not set)
endif

ifeq ($(strip $(DUT_DIR)),)
$(error DUT_DIR is not set)
endif

ifeq ($(strip $(TOPLEVEL)),)
$(error TOPLEVEL is not set)
endif

ifeq ($(strip $(MODULE)),)
$(error MODULE is not set)
endif

ifeq ($(strip $(VERILOG_TEST_SOURCES)),)
$(error VERILOG_TEST_SOURCES is not set)
endif

ifeq ($(strip $(VERILOG_DIR)),)
$(error VERILOG_DIR is not set)
endif

ifeq ($(strip $(VERILOG_SOURCES)),)
$(error VERILOG_SOURCES is not set)
endif

COCOTB_HDL_TIMEUNIT = 1ns
COCOTB_HDL_TIMEPRECISION = 1ps

VERIBLE_FORMAT_CMD := verible-verilog-format \
	--inplace \
	--column_limit 119 \
	--indentation_spaces 4 \
	--line_break_penalty 4 \
	--wrap_spaces 4 \
	--port_declarations_alignment align \
	--port_declarations_indentation indent \
	--formal_parameters_alignment align \
	--formal_parameters_indentation indent \
	--assignment_statement_alignment align \
	--enum_assignment_statement_alignment align \
	--class_member_variable_alignment align \
	--module_net_variable_alignment align \
	--named_parameter_alignment align \
	--named_parameter_indentation indent \
	--named_port_alignment align \
	--named_port_indentation indent \
	--struct_union_members_alignment align

ifeq ($(SIM), icarus)
	COMPILE_ARGS += -g2012 -Dfunctional -DSIMULATION
	PLUSARGS += -fst -Dfunctional -DSIMULATION
else ifeq ($(SIM), verilator)
	COMPILE_ARGS += \
		-CFLAGS "-std=c++20 -fcoroutines" \
		-Wno-SELRANGE \
		-Wno-WIDTH \
		-Wno-BLKANDNBLK \
		-Wno-MINTYPMAXDLY \
		--bbox-unsup \
		--timing \
		-Dfunctional \
		-DSIMULATION

	ifeq ($(WAVES), 1)
		COMPILE_ARGS += --trace-fst
	endif
else ifeq ($(SIM), vcs)
	PLUSARGS += \
		-debug_access+all \
		-debug_region=cell+lib+encrypt \
		+acc+3 \
		+incdir+$(VERILOG_DIR) \
		+incdir+$(DUT_DIR) \
		+define+functional \
		+define+SIMULATION \
		+lint=all,noVCDE,noONGS,noUI \
		-error=PCWM-L \
		-error=noZMMCM \
		+warn=noPISB \
		+rad \
		+vcs+lic+wait \
		+vc+list \
		+systemverilogext+.sv+.svi+.svh+.svt \
		+libext+.sv \
		+v2k \
		+verilog2001ext+.v95+.vt+.vp \
		+libext+.v

	COMPILE_ARGS += -timescale=1ns/10ps \
		-assert svaext \
		-sverilog

	ifeq ($(LINT), 1)
		PLUSARGS += +lint=all,noVCDE,noONGS,noUI,noULCO
	endif

	ifeq ($(WAVES), 1)
		VERILOG_SOURCES += $(DUT_DIR)/$(SIM_BUILD)/vcs_dump.v
		COMPILE_ARGS += -top vcs_dump -lca -kdb
		SIM_ARGS += +fsdb+autoflush
	endif
else ifeq ($(SIM), xcelium)
	PLUSARGS += \
		+define+functional \
		+define+SIMULATION \
		-ALLOWREDEFINITION
	COMPILE_ARGS += \
		-sysv \
		-sysv_ext +.v \
		-vlog_ext .vp,.vs
endif

include $(shell cocotb-config --makefiles)/Makefile.sim

define DUMP_WAVE_VCS
module vcs_dump();
/* waveform */
initial begin
    $$fsdbDumpfile("$(DUT_DIR)/$(SIM_BUILD)/$(TOPLEVEL).fsdb");
    $$fsdbDumpSVA;
    $$fsdbDumpvars(0, $(TOPLEVEL), "+all", "+power", "+struct", "+mda");
    $$fsdbDumpon;
end
final begin
    $$fsdbDumpflush;
end
endmodule
endef
export DUMP_WAVE_VCS

CUSTOM_COMPILE_DEPS+= $(DUT_DIR)/$(SIM_BUILD)/sim.fl $(DUT_DIR)/$(SIM_BUILD)/vcs_dump.v

$(DUT_DIR)/$(SIM_BUILD)/sim.fl: $(VERILOG_SOURCES) | $(SIM_BUILD)
	@{ for f in $(VERILOG_SOURCES); do printf '%s\n' "$$f"; done; } > $@

$(DUT_DIR)/$(SIM_BUILD)/vcs_dump.v: | $(SIM_BUILD)
	@echo "$$DUMP_WAVE_VCS" > $@

verdi: $(DUT_DIR)/$(SIM_BUILD)/sim.fl
	@verdi \
		-nolog \
		-sv \
		-2009 \
		+systemverilogext+.sv+.svi+.svh+.svt \
		+libext+.sv \
		+v2k \
		+verilog2001ext+.v95+.vt+.vp \
		+libext+.v \
		+incdir+$(VERILOG_DIR) \
		+incdir+$(DUT_DIR) \
		+incdir+$(DUT_DIR)/$(SIM_BUILD) \
		+define+functional \
		+define+SIMULATION \
		-elab $(DUT_DIR)/$(SIM_BUILD)/simv.daidir/kdb \
		-ssf $(DUT_DIR)/$(SIM_BUILD)/$(TOPLEVEL).fsdb

format:
	@$(VERIBLE_FORMAT_CMD) $(VERILOG_TEST_SOURCES)

test: sim
	@grep -i 'failed' $(DUT_DIR)/results.xml && exit 1 || exit 0

clean::
	@rm -f $(DUT_DIR)/$(SIM_BUILD)/vcs_dump.v
	@rm -f $(DUT_DIR)/$(SIM_BUILD)/sim.fl
	@rm -f $(DUT_DIR)/$(SIM_BUILD)/$(TOPLEVEL).fsdb
	@rm -f $(DUT_DIR)/novas.*
	@rm -f $(DUT_DIR)/*.log
	@rm -f $(DUT_DIR)/*.xml
	@rm -f $(DUT_DIR)/*.fst
	@rm -f $(DUT_DIR)/*.fsdb
	@rm -f $(DUT_DIR)/*.gtkw
	@rm -f $(DUT_DIR)/ucli.key
	@rm -rf $(DUT_DIR)/__pycache__
	@rm -rf $(DUT_DIR)/verdiLog
	@rm -rf $(DUT_DIR)/xrun.*

.PHONY: verdi format test
