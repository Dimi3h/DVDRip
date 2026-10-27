/*
 * CSS helpers for the tests, built on libdvdcss's own code (css.c is included rather than linked
 * because the functions needed are static there):
 *
 *   css_tool vectors
 *       Prints CryptKey results, the CSS authentication cipher, for the Kotlin port in core:
 *       key type, variant, challenge (10 bytes) and result (5 bytes) in hex, one per line.
 *   css_tool scramble [--no-pattern] <image> <title key, 10 hex digits> <first:count>...
 *       Scrambles the audio and video packs in the given block ranges of a DVD image with the
 *       title key, as on a CSS-protected disc, so the tests can rip one. Navigation packs stay
 *       clear. Every sector is checked to unscramble back to the original. --no-pattern first
 *       overwrites the last 48 clear bytes of each of those packs with bytes that never repeat,
 *       which leaves libdvdcss's title key attack nothing to work with.
 */
#include "css.c"

#include <stdlib.h>

static int vectors(void) {
    uint32_t state = 0x12345678;
    for (int type = 0; type < 3; type++) {
        for (int variant = 0; variant < 32; variant++) {
            for (int sample = 0; sample < 3; sample++) {
                uint8_t challenge[10];
                uint8_t key[5];
                for (int i = 0; i < 10; i++) {
                    state = state * 1103515245u + 12345u;
                    challenge[i] = (uint8_t) (state >> 16);
                }
                CryptKey(type, variant, challenge, key);
                printf("%d %d ", type, variant);
                for (int i = 0; i < 10; i++) printf("%02x", challenge[i]);
                printf(" ");
                for (int i = 0; i < 5; i++) printf("%02x", key[i]);
                printf("\n");
            }
        }
    }
    return 0;
}

/* The inverse of dvdcss_unscramble: the same key stream, the substitution table run backwards. */
static void scramble_sector(const dvd_key key, uint8_t *sector, const uint8_t *inverse_tab1) {
    unsigned int t1 = (key[0] ^ sector[0x54]) | 0x100;
    unsigned int t2 = key[1] ^ sector[0x55];
    unsigned int t3 = (key[2] | (key[3] << 8) | (key[4] << 16)) ^ (sector[0x56] | (sector[0x57] << 8) | (sector[0x58] << 16));
    unsigned int t4 = t3 & 7;
    unsigned int t5 = 0;
    unsigned int t6;
    t3 = t3 * 2 + 8 - t4;
    for (int i = 0x80; i < DVDCSS_BLOCK_SIZE; i++) {
        t4 = p_css_tab2[t2] ^ p_css_tab3[t1];
        t2 = t1 >> 1;
        t1 = ((t1 & 1) << 8) ^ t4;
        t4 = p_css_tab5[t4];
        t6 = (((((((t3 >> 3) ^ t3) >> 1) ^ t3) >> 8) ^ t3) >> 5) & 0xff;
        t3 = (t3 << 8) | t6;
        t6 = p_css_tab4[t6];
        t5 += t6 + t4;
        sector[i] = inverse_tab1[sector[i] ^ (t5 & 0xff)];
        t5 >>= 8;
    }
}

static int scramble(int argc, char **argv) {
    int no_pattern = argc > 2 && strcmp(argv[2], "--no-pattern") == 0;
    if (no_pattern) {
        argv++;
        argc--;
    }
    if (argc < 5) {
        fprintf(stderr, "usage: css_tool scramble [--no-pattern] <image> <key> <first:count>...\n");
        return 2;
    }
    dvd_key key;
    if (strlen(argv[3]) != 10) {
        fprintf(stderr, "The key has 10 hex digits\n");
        return 2;
    }
    for (int i = 0; i < 5; i++) {
        char pair[3] = {argv[3][2 * i], argv[3][2 * i + 1], 0};
        key[i] = (uint8_t) strtoul(pair, NULL, 16);
    }
    uint8_t inverse_tab1[256];
    for (int i = 0; i < 256; i++) inverse_tab1[p_css_tab1[i]] = (uint8_t) i;

    FILE *image = fopen(argv[2], "r+b");
    if (image == NULL) {
        perror(argv[2]);
        return 1;
    }
    long scrambled = 0;
    for (int range = 4; range < argc; range++) {
        long first = strtol(argv[range], NULL, 0);
        const char *colon = strchr(argv[range], ':');
        long count = colon != NULL ? strtol(colon + 1, NULL, 0) : 1;
        for (long block = first; block < first + count; block++) {
            uint8_t sector[DVDCSS_BLOCK_SIZE];
            uint8_t original[DVDCSS_BLOCK_SIZE];
            if (fseek(image, block * DVDCSS_BLOCK_SIZE, SEEK_SET) != 0 || fread(sector, 1, sizeof(sector), image) != sizeof(sector)) {
                fprintf(stderr, "Cannot read block %ld\n", block);
                return 1;
            }
            /* A pack with a PES packet first; not the system header (navigation), padding or private stream 2. */
            if (sector[0] != 0 || sector[1] != 0 || sector[2] != 1 || sector[3] != 0xba) continue;
            if (sector[0x0e] != 0 || sector[0x0f] != 0 || sector[0x10] != 1) continue;
            if (sector[0x11] == 0xbb || sector[0x11] == 0xbe || sector[0x11] == 0xbf) continue;
            if (no_pattern) {
                /* AttackPattern looks for a cycle of 2 to 47 bytes ending at 0x7f; 167 is odd, so
                   these 48 bytes are all different. */
                for (int k = 0; k < 48; k++) sector[0x7f - k] = (uint8_t) (k * 167 + block);
            }
            memcpy(original, sector, sizeof(sector));
            sector[0x14] = (uint8_t) ((sector[0x14] & ~0x30) | 0x10);
            scramble_sector(key, sector, inverse_tab1);

            uint8_t check[DVDCSS_BLOCK_SIZE];
            memcpy(check, sector, sizeof(check));
            dvdcss_unscramble(key, check);
            check[0x14] = original[0x14];
            if (memcmp(check, original, sizeof(check)) != 0) {
                fprintf(stderr, "Block %ld does not unscramble back\n", block);
                return 1;
            }
            if (fseek(image, block * DVDCSS_BLOCK_SIZE, SEEK_SET) != 0 || fwrite(sector, 1, sizeof(sector), image) != sizeof(sector)) {
                fprintf(stderr, "Cannot write block %ld\n", block);
                return 1;
            }
            scrambled++;
        }
    }
    fclose(image);
    printf("%ld blocks scrambled\n", scrambled);
    return 0;
}

int main(int argc, char **argv) {
    if (argc >= 2 && strcmp(argv[1], "vectors") == 0) return vectors();
    if (argc >= 2 && strcmp(argv[1], "scramble") == 0) return scramble(argc, argv);
    fprintf(stderr, "usage: css_tool vectors | scramble [--no-pattern] <image> <key> <first:count>...\n");
    return 2;
}
