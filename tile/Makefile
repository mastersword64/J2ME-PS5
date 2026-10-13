ifdef PS5_PAYLOAD_SDK
    include $(PS5_PAYLOAD_SDK)/toolchain/prospero.mk
else
    $(error PS5_PAYLOAD_SDK is undefined)
endif

# Fake-signing parameters, as in the SDK's install_app sample.
SDK_VERSION := 0x07590001
AUTHID   := 0x3800000000000022
AUTHINFO := 00 00 00 00 00 00 00 00 00 00 00 00 00 1C 00 40 00 FF 00 00 00 00 \
            00 80 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 80 \
            00 40 00 40 00 00 00 00 00 00 00 80 00 00 00 00 00 00 00 08 00 40 \
            FF FF 00 00 00 F0 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 \
            00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 \
            00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 \
            00 00 00 00

all: eboot.bin

eboot.o: eboot.c ../launcher/ps5j2me-launch.elf
	$(CC) -c -O1 -Wall -ffreestanding -fno-builtin -fno-stack-protector -o $@ eboot.c

eboot.elf: eboot.o eboot.x
	$(LD) --static -T eboot.x -o $@ eboot.o

eboot.bin: eboot.elf make_fself.py
	python3 make_fself.py --ptype fake --paid $(AUTHID) \
	    --auth-info "$(AUTHINFO)" \
	    --app-version $(SDK_VERSION) --fw-version $(SDK_VERSION) eboot.elf $@

clean:
	rm -f eboot.o eboot.elf eboot.bin
