ifdef PS5_PAYLOAD_SDK
    include $(PS5_PAYLOAD_SDK)/toolchain/prospero.mk
else
    $(error PS5_PAYLOAD_SDK is undefined)
endif

ELF    := ps5j2me-launch.elf
SRCS   := main.c vendor/hbldr.c vendor/elfldr.c vendor/pt.c
CFLAGS := -O1 -Wall -Ishim -Ivendor
LDADD  := -lkernel_sys -lSceSystemService -lSceUserService -lpthread

all: $(ELF)

$(ELF): $(SRCS)
	$(CC) $(CFLAGS) -o $@ $(SRCS) $(LDADD)

clean:
	rm -f $(ELF)
