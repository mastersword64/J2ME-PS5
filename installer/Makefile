ifdef PS5_PAYLOAD_SDK
    include $(PS5_PAYLOAD_SDK)/toolchain/prospero.mk
else
    $(error PS5_PAYLOAD_SDK is undefined)
endif

ELF := ps5j2me-install.elf

all: $(ELF)

$(ELF): main.c icon0.png ../app/eboot.elf ../tile/eboot.bin ../java/ps5frontend.jar ../java/freej2me-lr.jar ../java/freej2me-new.jar ../java/ps5patch-classic.jar ../java/ps5patch-new.jar
	$(CC) -O2 -Wall -o $@ main.c -lSceUserService -lSceAppInstUtil

clean:
	rm -f $(ELF)
