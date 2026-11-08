// Rip pipeline: libdvdread cell walk -> custom AVIOContext -> MPEG-PS demux -> (copy | decode,
// filter, VideoToolbox encode) -> Matroska mux, streamed straight to the destination file.
#include "dr_internal.h"

#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#include <dvdread/nav_read.h>
#include <dvdread/nav_types.h>

#include <libavcodec/avcodec.h>
#include <libavfilter/avfilter.h>
#include <libavfilter/buffersink.h>
#include <libavfilter/buffersrc.h>
#include <libavformat/avformat.h>
#include <libavutil/audio_fifo.h>
#include <libavutil/channel_layout.h>
#include <libavutil/opt.h>
#include <libavutil/time.h>
#include <libswresample/swresample.h>

#define CHUNK_BLOCKS 32
#define READ_RETRIES 3
#define MAX_CONSECUTIVE_FAILURES 64
#define AVIO_BUFFER 65536
#define TB90K ((AVRational){1, 90000})

enum { OUT_VIDEO, OUT_AUDIO_COPY, OUT_AUDIO_PCM, OUT_SUB };

typedef struct {
    int kind;
    int ps_id;           // MPEG-PS stream id
    AVStream *ost;
    int64_t last_dts;    // in 90 kHz, for monotonicity
    int cur_cell;        // cell the last packet of this stream belonged to
    // Audio re-encode (LPCM -> PCM/FLAC)
    AVCodecContext *dec;
    AVCodecContext *enc;
    SwrContext *swr;
    AVAudioFifo *fifo;
    int64_t fifo_pts;    // in 1/sample_rate
    int fifo_pts_valid;
    int ifo_channels;
    int ifo_rate;
    int ifo_bits;
} out_track;

typedef struct {
    dr_disc *disc;
    const dr_rip_options *opt;
    dr_rip_result *res;
    dr_title_layout L;
    dvd_file_t *file;

    // block reader
    int cell;
    int cell_started;
    uint32_t sector;
    uint8_t *chunk;
    int chunk_len;
    int chunk_pos;
    int64_t stream_pos;
    int64_t *cell_byte_start;
    int64_t *cell_offset90;
    int64_t *cell_raw_start90; // vobu_s_ptm of the first VOBU of the cell
    int *cell_offset_known;
    int64_t sectors_done;
    int consecutive_fail;
    int any_success;
    int reader_error;
    int eof;
    int is_device;

    // demux / mux
    AVFormatContext *ic;
    AVFormatContext *oc;
    int *stream_map;     // input stream index -> track index (-1 none, -2 unresolved)
    int stream_map_size;
    out_track tracks[1 + 8 + 32];
    int track_count;
    int video_in_index;

    // video encode
    AVCodecContext *vdec;
    AVCodecContext *venc;
    AVFilterGraph *graph;
    AVFilterContext *bsrc;
    AVFilterContext *bsink;
    AVRational sar;
    int64_t last_enc_pts;
    int64_t frames_encoded;
    AVRational fps;

    int debug;           // DISCRIP_DEBUG=1 logs every packet to stderr

    // progress
    double t0;
    double last_report;
    int64_t media_pts90;
} rip_ctx;

static double now_seconds(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec + ts.tv_nsec / 1e9;
}

static void set_error(rip_ctx *r, int code, const char *fmt, ...) __attribute__((format(printf, 3, 4)));
static void set_error(rip_ctx *r, int code, const char *fmt, ...) {
    if (r->res->code != DR_OK) return; // keep the first error
    r->res->code = code;
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(r->res->message, sizeof r->res->message, fmt, ap);
    va_end(ap);
    dr_logf(r->disc, 0, "%s", r->res->message);
}

static int cancelled(rip_ctx *r) { return r->opt->cancel && *r->opt->cancel; }

static void report(rip_ctx *r, int force) {
    if (!r->opt->progress) return;
    double t = now_seconds();
    if (!force && t - r->last_report < 0.25) return;
    r->last_report = t;
    dr_progress p;
    memset(&p, 0, sizeof p);
    p.phase = force == 2 ? DR_PHASE_FINISHING : (r->venc ? DR_PHASE_ENCODING : DR_PHASE_READING);
    p.bytes_done = r->sectors_done * 2048;
    p.bytes_total = r->L.sectors * 2048;
    p.elapsed_seconds = t - r->t0;
    p.media_seconds_done = r->media_pts90 > 0 ? r->media_pts90 / 90000.0 : 0;
    p.media_seconds_total = r->L.duration90 / 90000.0;
    p.encoder_fps = (r->venc && p.elapsed_seconds > 0) ? r->frames_encoded / p.elapsed_seconds : 0;
    p.output_bytes = (r->oc && r->oc->pb) ? avio_tell(r->oc->pb) : 0;
    p.bad_sectors = r->res->bad_sectors;
    r->opt->progress(r->opt->ctx, &p);
}

// ---------------------------------------------------------------------------------------------
// Block reader

static int is_nav_pack(const uint8_t *b) {
    return b[0] == 0 && b[1] == 0 && b[2] == 1 && b[3] == 0xba &&
           b[0x26] == 0 && b[0x27] == 0 && b[0x28] == 1 && b[0x29] == 0xbf && b[0x2c] == 0x00;
}

static void note_bad_range(rip_ctx *r, uint32_t start, uint32_t count) {
    dr_rip_result *res = r->res;
    res->bad_sectors += (int)count;
    if (res->bad_range_count > 0) {
        int i = res->bad_range_count - 1;
        if (res->bad_range_start[i] + res->bad_range_length[i] == start) {
            res->bad_range_length[i] += count;
            return;
        }
    }
    if (res->bad_range_count < DR_MAX_BAD_RANGES) {
        res->bad_range_start[res->bad_range_count] = start;
        res->bad_range_length[res->bad_range_count] = count;
        res->bad_range_count++;
    }
}

static int device_gone(rip_ctx *r) {
    return r->is_device && access(r->disc->path, F_OK) != 0;
}

static int read_blocks(rip_ctx *r, uint32_t sector, int n) {
    for (int attempt = 0; attempt < READ_RETRIES; attempt++) {
        ssize_t got = DVDReadBlocks(r->file, (int)sector, (size_t)n, r->chunk);
        if (got == n) return 1;
        if (device_gone(r)) return 0;
        dr_logf(r->disc, 1, "read error at sector %u (+%d), attempt %d", sector, n, attempt + 1);
    }
    return 0;
}

// Fills r->chunk with the next decrypted blocks of the title. Returns 1 on data, 0 at end, -1 on fatal.
static int fill_chunk(rip_ctx *r) {
    while (r->cell < r->L.cell_count) {
        if (cancelled(r)) return -1;
        dr_cell *c = &r->L.cells[r->cell];
        if (!r->cell_started) {
            r->cell_started = 1;
            r->sector = c->first_sector;
            r->cell_byte_start[r->cell] = r->stream_pos;
        }
        if (r->sector > c->last_sector) {
            r->cell++;
            r->cell_started = 0;
            continue;
        }
        uint32_t left = c->last_sector - r->sector + 1;
        int n = left < CHUNK_BLOCKS ? (int)left : CHUNK_BLOCKS;
        if (read_blocks(r, r->sector, n)) {
            r->any_success = 1;
            r->consecutive_fail = 0;
            if (!r->cell_offset_known[r->cell]) {
                for (int i = 0; i < n; i++) {
                    uint8_t *b = r->chunk + (size_t)i * 2048;
                    if (!is_nav_pack(b)) continue;
                    pci_t pci;
                    navRead_PCI(&pci, b + 0x2d);
                    r->cell_offset90[r->cell] = c->start90 - (int64_t)pci.pci_gi.vobu_s_ptm;
                    r->cell_raw_start90[r->cell] = (int64_t)pci.pci_gi.vobu_s_ptm;
                    r->cell_offset_known[r->cell] = 1;
                    break;
                }
            }
            r->chunk_len = n * 2048;
            r->chunk_pos = 0;
            r->stream_pos += r->chunk_len;
            r->sector += (uint32_t)n;
            r->sectors_done += n;
            return 1;
        }
        if (device_gone(r)) {
            r->reader_error = DR_ERR_DEVICE_GONE;
            return -1;
        }
        note_bad_range(r, r->sector, (uint32_t)n);
        r->sector += (uint32_t)n;
        r->sectors_done += n;
        r->consecutive_fail++;
        if (!r->any_success && r->consecutive_fail >= 4) {
            r->reader_error = DR_ERR_READ_REFUSED;
            return -1;
        }
        if (r->consecutive_fail >= MAX_CONSECUTIVE_FAILURES) {
            r->reader_error = DR_ERR_READ;
            return -1;
        }
    }
    return 0;
}

static int avio_read_cb(void *opaque, uint8_t *buf, int size) {
    rip_ctx *r = opaque;
    if (r->chunk_pos >= r->chunk_len) {
        if (r->eof) return AVERROR_EOF;
        int rc = fill_chunk(r);
        if (rc == 0) {
            r->eof = 1;
            return AVERROR_EOF;
        }
        if (rc < 0) return AVERROR_EXIT;
    }
    int n = r->chunk_len - r->chunk_pos;
    if (n > size) n = size;
    memcpy(buf, r->chunk + r->chunk_pos, (size_t)n);
    r->chunk_pos += n;
    return n;
}

static int interrupt_cb(void *opaque) { return cancelled((rip_ctx *)opaque); }

static int cell_for_pos(rip_ctx *r, int64_t pos) {
    int found = -1, lo = 0, hi = r->L.cell_count - 1;
    while (lo <= hi) {
        int mid = (lo + hi) / 2;
        if (r->cell_byte_start[mid] <= pos) {
            found = mid;
            lo = mid + 1;
        } else {
            hi = mid - 1;
        }
    }
    return found;
}

static int raw_in_cell(rip_ctx *r, int c, int64_t ts) {
    if (!r->cell_offset_known[c]) return 0;
    int64_t s = r->cell_raw_start90[c];
    return ts >= s - 90000 && ts <= s + r->L.cells[c].duration90 + 90000;
}

static int64_t cell_offset(rip_ctx *r, int c) {
    for (int k = c; k >= 0; k--)
        if (r->cell_offset_known[k]) return r->cell_offset90[k];
    for (int k = c + 1; k < r->L.cell_count; k++)
        if (r->cell_offset_known[k]) return r->cell_offset90[k];
    return 0;
}

// Timestamp offset that maps a packet onto the title timeline. Packets that start a PES carry
// their byte position, which identifies the cell exactly. Parsed packets that were split out of a
// PES (pos = -1) are matched by their raw PTS against the cells following the stream's last cell.
static int64_t offset_for_packet(rip_ctx *r, out_track *t, const AVPacket *pkt) {
    if (pkt->pos >= 0) {
        int c = cell_for_pos(r, pkt->pos);
        if (c >= 0) {
            t->cur_cell = c;
            return cell_offset(r, c);
        }
    }
    int64_t ts = pkt->pts != AV_NOPTS_VALUE ? pkt->pts : pkt->dts;
    int c = t->cur_cell;
    if (ts != AV_NOPTS_VALUE && !raw_in_cell(r, c, ts) && c > 0 && raw_in_cell(r, c - 1, ts)) {
        c = c - 1; // audio of the previous VOBU that was muxed after the cell boundary
    } else if (ts != AV_NOPTS_VALUE && !raw_in_cell(r, c, ts)) {
        int limit = r->cell < r->L.cell_count ? r->cell : r->L.cell_count - 1;
        for (int k = c + 1; k <= limit; k++) {
            if (raw_in_cell(r, k, ts)) {
                t->cur_cell = c = k;
                break;
            }
        }
    }
    return cell_offset(r, c);
}

// ---------------------------------------------------------------------------------------------
// IFO helpers

static int ifo_audio_attr(rip_ctx *r, int ps_id, audio_attr_t **out) {
    ifo_handle_t *ifo = dr_vts_ifo(r->disc, r->L.vts);
    pgc_t *pgc = r->L.first_pgc;
    for (int i = 0; i < ifo->vtsi_mat->nr_of_vts_audio_streams && i < 8; i++) {
        uint16_t ctl = pgc->audio_control[i];
        if (!(ctl & 0x8000)) continue;
        audio_attr_t *aa = &ifo->vtsi_mat->vts_audio_attr[i];
        if (dr_audio_stream_id(aa->audio_format, (ctl >> 8) & 7) == ps_id) {
            *out = aa;
            return 0;
        }
    }
    return -1;
}

static AVStream *find_input(rip_ctx *r, int ps_id) {
    for (unsigned i = 0; i < r->ic->nb_streams; i++)
        if (r->ic->streams[i]->id == ps_id) return r->ic->streams[i];
    return NULL;
}

static int map_errno(int averr) {
    if (averr == AVERROR(ENOSPC)) return DR_ERR_NO_SPACE;
    return DR_ERR_OUTPUT;
}

// ---------------------------------------------------------------------------------------------
// Output setup

static int setup_video(rip_ctx *r, AVStream *ist) {
    ifo_handle_t *ifo = dr_vts_ifo(r->disc, r->L.vts);
    video_attr_t *va = &ifo->vtsi_mat->vts_video_attr;
    int w = ist->codecpar->width, h = ist->codecpar->height;
    if (w <= 0 || h <= 0) {
        set_error(r, DR_ERR_DEMUX, "Video stream has no dimensions");
        return -1;
    }
    int dar_num = va->display_aspect_ratio == 3 ? 16 : 4, dar_den = va->display_aspect_ratio == 3 ? 9 : 3;
    av_reduce(&r->sar.num, &r->sar.den, (int64_t)dar_num * h, (int64_t)dar_den * w, 1 << 16);
    int pal = va->video_format == 1;
    r->fps = dr_frame_rate_code(&r->L.first_pgc->playback_time) == 3 || !pal ? (AVRational){30000, 1001}
                                                                               : (AVRational){25, 1};

    out_track *t = &r->tracks[r->track_count++];
    t->kind = OUT_VIDEO;
    t->ps_id = ist->id;
    t->last_dts = AV_NOPTS_VALUE;
    t->ost = avformat_new_stream(r->oc, NULL);
    if (!t->ost) return -1;

    if (r->opt->video_mode == DR_VIDEO_COPY) {
        avcodec_parameters_copy(t->ost->codecpar, ist->codecpar);
        t->ost->codecpar->codec_tag = 0;
        t->ost->codecpar->sample_aspect_ratio = r->sar;
        t->ost->sample_aspect_ratio = r->sar;
        t->ost->avg_frame_rate = r->fps;
        t->ost->time_base = (AVRational){1, 1000};
        r->res->video_mode_used = DR_VIDEO_COPY;
        return 0;
    }

    // Lossy: software MPEG-2 decode, then VideoToolbox.
    const AVCodec *dec = avcodec_find_decoder(ist->codecpar->codec_id);
    r->vdec = avcodec_alloc_context3(dec);
    avcodec_parameters_to_context(r->vdec, ist->codecpar);
    r->vdec->pkt_timebase = TB90K;
    r->vdec->thread_count = 0;
    if (avcodec_open2(r->vdec, dec, NULL) < 0) {
        set_error(r, DR_ERR_ENCODER, "Cannot open the MPEG-2 decoder");
        return -1;
    }

    int mode = r->opt->video_mode;
    for (int attempt = 0; attempt < 2; attempt++) {
        const char *name = mode == DR_VIDEO_HEVC ? "hevc_videotoolbox" : "h264_videotoolbox";
        const AVCodec *enc = avcodec_find_encoder_by_name(name);
        AVCodecContext *e = enc ? avcodec_alloc_context3(enc) : NULL;
        if (e) {
            e->width = w;
            e->height = h;
            e->pix_fmt = AV_PIX_FMT_NV12;
            e->time_base = TB90K;
            e->framerate = r->fps;
            e->sample_aspect_ratio = r->sar;
            e->bit_rate = r->opt->video_bitrate;
            e->gop_size = (int)(av_q2d(r->fps) * 5 + 0.5);
            e->flags |= AV_CODEC_FLAG_GLOBAL_HEADER;
            e->color_range = AVCOL_RANGE_MPEG;
            e->color_primaries = pal ? AVCOL_PRI_BT470BG : AVCOL_PRI_SMPTE170M;
            e->color_trc = pal ? AVCOL_TRC_GAMMA28 : AVCOL_TRC_SMPTE170M;
            e->colorspace = pal ? AVCOL_SPC_BT470BG : AVCOL_SPC_SMPTE170M;
            av_opt_set(e->priv_data, "profile", mode == DR_VIDEO_HEVC ? "main" : "high", 0);
            av_opt_set_int(e->priv_data, "allow_sw", 0, 0);
            int rc = avcodec_open2(e, enc, NULL);
            if (rc == 0) {
                r->venc = e;
                break;
            }
            char err[128];
            av_strerror(rc, err, sizeof err);
            dr_logf(r->disc, 0, "%s could not be opened: %s", name, err);
            avcodec_free_context(&e);
        }
        if (mode == DR_VIDEO_HEVC && r->opt->allow_h264_fallback) {
            mode = DR_VIDEO_H264;
            r->res->fell_back_to_h264 = 1;
            continue;
        }
        break;
    }
    if (!r->venc) {
        set_error(r, DR_ERR_ENCODER, "No hardware video encoder available");
        return -1;
    }
    r->res->video_mode_used = mode;
    avcodec_parameters_from_context(t->ost->codecpar, r->venc);
    t->ost->time_base = (AVRational){1, 1000};
    t->ost->sample_aspect_ratio = r->sar;
    t->ost->avg_frame_rate = r->fps;
    r->last_enc_pts = AV_NOPTS_VALUE;
    return 0;
}

static enum AVCodecID copy_codec_for_format(int audio_format) {
    switch (audio_format) {
    case 0: return AV_CODEC_ID_AC3;
    case 2: return AV_CODEC_ID_MP2;
    case 3: return AV_CODEC_ID_MP2;
    case 6: return AV_CODEC_ID_DTS;
    default: return AV_CODEC_ID_NONE;
    }
}

static int setup_audio(rip_ctx *r, int ps_id, const char *lang, const char *name, int is_default) {
    audio_attr_t *aa = NULL;
    if (ifo_audio_attr(r, ps_id, &aa) != 0) {
        set_error(r, DR_ERR_DEMUX, "Audio stream 0x%x is not listed in the IFO", ps_id);
        return -1;
    }
    out_track *t = &r->tracks[r->track_count++];
    t->ps_id = ps_id;
    t->last_dts = AV_NOPTS_VALUE;
    t->ifo_channels = aa->channels + 1;
    t->ifo_rate = aa->sample_frequency == 1 ? 96000 : 48000;
    t->ifo_bits = aa->quantization == 0 ? 16 : aa->quantization == 1 ? 20 : 24;
    t->ost = avformat_new_stream(r->oc, NULL);
    if (!t->ost) return -1;
    AVStream *ist = find_input(r, ps_id);

    if (aa->audio_format == 4) {
        // LPCM: decoded and stored bit-exact as PCM, or as FLAC (lossless) when requested.
        t->kind = OUT_AUDIO_PCM;
        int wide = t->ifo_bits > 16;
        const AVCodec *enc = avcodec_find_encoder(r->opt->lpcm_to_flac ? AV_CODEC_ID_FLAC
                                                  : wide ? AV_CODEC_ID_PCM_S24LE : AV_CODEC_ID_PCM_S16LE);
        t->enc = avcodec_alloc_context3(enc);
        t->enc->sample_rate = t->ifo_rate;
        av_channel_layout_default(&t->enc->ch_layout, t->ifo_channels);
        t->enc->sample_fmt = wide ? AV_SAMPLE_FMT_S32 : AV_SAMPLE_FMT_S16;
        t->enc->bits_per_raw_sample = t->ifo_bits == 20 ? 24 : t->ifo_bits;
        t->enc->time_base = (AVRational){1, t->ifo_rate};
        t->enc->flags |= AV_CODEC_FLAG_GLOBAL_HEADER;
        if (r->opt->lpcm_to_flac) t->enc->compression_level = 8;
        int rc = avcodec_open2(t->enc, enc, NULL);
        if (rc < 0 && r->opt->lpcm_to_flac) {
            dr_logf(r->disc, 0, "FLAC encoder refused this layout; keeping PCM");
            avcodec_free_context(&t->enc);
            enc = avcodec_find_encoder(wide ? AV_CODEC_ID_PCM_S24LE : AV_CODEC_ID_PCM_S16LE);
            t->enc = avcodec_alloc_context3(enc);
            t->enc->sample_rate = t->ifo_rate;
            av_channel_layout_default(&t->enc->ch_layout, t->ifo_channels);
            t->enc->sample_fmt = wide ? AV_SAMPLE_FMT_S32 : AV_SAMPLE_FMT_S16;
            t->enc->bits_per_raw_sample = t->ifo_bits == 20 ? 24 : t->ifo_bits;
            t->enc->time_base = (AVRational){1, t->ifo_rate};
            rc = avcodec_open2(t->enc, enc, NULL);
        }
        if (rc < 0) {
            set_error(r, DR_ERR_ENCODER, "Cannot open the audio encoder for LPCM");
            return -1;
        }
        avcodec_parameters_from_context(t->ost->codecpar, t->enc);
        t->fifo = av_audio_fifo_alloc(t->enc->sample_fmt, t->ifo_channels, 4096);
    } else {
        t->kind = OUT_AUDIO_COPY;
        if (ist) {
            avcodec_parameters_copy(t->ost->codecpar, ist->codecpar);
        } else {
            // Stream not seen during probing (starts late): describe it from the IFO.
            t->ost->codecpar->codec_type = AVMEDIA_TYPE_AUDIO;
            t->ost->codecpar->codec_id = copy_codec_for_format(aa->audio_format);
            t->ost->codecpar->sample_rate = t->ifo_rate;
            av_channel_layout_default(&t->ost->codecpar->ch_layout, t->ifo_channels);
        }
        t->ost->codecpar->codec_tag = 0;
    }
    t->ost->time_base = (AVRational){1, 1000};
    av_dict_set(&t->ost->metadata, "language", lang && *lang ? lang : "und", 0);
    if (name && *name) av_dict_set(&t->ost->metadata, "title", name, 0);
    t->ost->disposition = is_default ? AV_DISPOSITION_DEFAULT : 0;
    return 0;
}

static int setup_subtitle(rip_ctx *r, int ps_id, const char *lang, const char *name, int width, int height) {
    out_track *t = &r->tracks[r->track_count++];
    t->kind = OUT_SUB;
    t->ps_id = ps_id;
    t->last_dts = AV_NOPTS_VALUE;
    t->ost = avformat_new_stream(r->oc, NULL);
    if (!t->ost) return -1;
    AVCodecParameters *p = t->ost->codecpar;
    p->codec_type = AVMEDIA_TYPE_SUBTITLE;
    p->codec_id = AV_CODEC_ID_DVD_SUBTITLE;
    p->width = width;
    p->height = height;
    // S_VOBSUB CodecPrivate: the .idx header with frame size and the PGC palette.
    dr_buf b = {0};
    dr_buf_printf(&b, "size: %dx%d\npalette: ", width, height);
    const uint32_t *pal = r->L.first_pgc->palette;
    for (int i = 0; i < 16; i++) {
        uint32_t v = pal[i];
        int y = (v >> 16) & 0xff, cr = (v >> 8) & 0xff, cb = v & 0xff;
        double R = 1.164 * (y - 16) + 1.596 * (cr - 128);
        double G = 1.164 * (y - 16) - 0.813 * (cr - 128) - 0.391 * (cb - 128);
        double B = 1.164 * (y - 16) + 2.018 * (cb - 128);
#define CLAMP8(x) ((x) < 0 ? 0 : (x) > 255 ? 255 : (int)((x) + 0.5))
        dr_buf_printf(&b, "%s%02x%02x%02x", i ? ", " : "", CLAMP8(R), CLAMP8(G), CLAMP8(B));
#undef CLAMP8
    }
    dr_buf_printf(&b, "\n");
    p->extradata = av_mallocz(b.len + AV_INPUT_BUFFER_PADDING_SIZE);
    memcpy(p->extradata, b.data, b.len);
    p->extradata_size = (int)b.len;
    free(b.data);
    t->ost->time_base = (AVRational){1, 1000};
    av_dict_set(&t->ost->metadata, "language", lang && *lang ? lang : "und", 0);
    if (name && *name) av_dict_set(&t->ost->metadata, "title", name, 0);
    t->ost->disposition = 0;
    return 0;
}

static int setup_chapters(rip_ctx *r) {
    int n = r->L.chapter_count;
    if (n <= 0) return 0;
    r->oc->chapters = av_calloc((size_t)n, sizeof(AVChapter *));
    if (!r->oc->chapters) return -1;
    int k = 0;
    for (int i = 0; i < r->L.cell_count && k < n; i++) {
        if (!r->L.cells[i].chapter_start) continue;
        AVChapter *ch = av_mallocz(sizeof *ch);
        if (!ch) return -1;
        ch->id = k + 1;
        ch->time_base = TB90K;
        ch->start = r->L.cells[i].start90;
        char title[64];
        snprintf(title, sizeof title, r->opt->chapter_name_format ? r->opt->chapter_name_format : "Chapter %d", k + 1);
        av_dict_set(&ch->metadata, "title", title, 0);
        r->oc->chapters[k++] = ch;
        r->oc->nb_chapters = (unsigned)k;
    }
    for (int i = 0; i < k; i++)
        r->oc->chapters[i]->end = i + 1 < k ? r->oc->chapters[i + 1]->start : r->L.duration90;
    return 0;
}

// ---------------------------------------------------------------------------------------------
// Packet handling

static int write_packet(rip_ctx *r, out_track *t, AVPacket *pkt, AVRational tb) {
    pkt->stream_index = t->ost->index;
    av_packet_rescale_ts(pkt, tb, t->ost->time_base);
    pkt->pos = -1;
    int rc = av_interleaved_write_frame(r->oc, pkt);
    if (rc < 0) {
        char err[128];
        av_strerror(rc, err, sizeof err);
        set_error(r, map_errno(rc), "Writing the MKV failed: %s", err);
    }
    return rc;
}

// SPU display duration from its control sequences (STP_DSP delay), in 90 kHz units.
static int64_t spu_duration90(const uint8_t *d, int size) {
    if (size < 4) return 0;
    int total = (d[0] << 8) | d[1];
    int off = (d[2] << 8) | d[3];
    if (total > size) total = size;
    int guard = 0;
    while (off + 4 <= total && guard++ < 32) {
        int delay = (d[off] << 8) | d[off + 1];
        int next = (d[off + 2] << 8) | d[off + 3];
        int p = off + 4;
        while (p < total) {
            uint8_t cmd = d[p++];
            if (cmd == 0x02) return (int64_t)delay * 1024;
            if (cmd == 0xff) break;
            switch (cmd) {
            case 0x00: case 0x01: break;
            case 0x03: case 0x04: p += 2; break;
            case 0x05: p += 6; break;
            case 0x06: p += 4; break;
            default: p = total; break;
            }
        }
        if (next == off) break;
        off = next;
    }
    return 0;
}

static int encode_video_frame(rip_ctx *r, AVFrame *frame) {
    out_track *vt = &r->tracks[0];
    if (frame) {
        if (frame->pts == AV_NOPTS_VALUE) frame->pts = r->last_enc_pts == AV_NOPTS_VALUE ? 0 : r->last_enc_pts + 1;
        if (r->last_enc_pts != AV_NOPTS_VALUE && frame->pts <= r->last_enc_pts) frame->pts = r->last_enc_pts + 1;
        r->last_enc_pts = frame->pts;
        frame->pict_type = AV_PICTURE_TYPE_NONE;
    }
    int rc = avcodec_send_frame(r->venc, frame);
    if (rc < 0 && rc != AVERROR_EOF) {
        set_error(r, DR_ERR_ENCODER, "The hardware video encoder failed during the rip");
        return rc;
    }
    AVPacket *out = av_packet_alloc();
    for (;;) {
        rc = avcodec_receive_packet(r->venc, out);
        if (rc == AVERROR(EAGAIN) || rc == AVERROR_EOF) break;
        if (rc < 0) {
            set_error(r, DR_ERR_ENCODER, "The hardware video encoder failed during the rip");
            av_packet_free(&out);
            return rc;
        }
        r->frames_encoded++;
        if (out->pts != AV_NOPTS_VALUE) r->media_pts90 = out->pts;
        if (write_packet(r, vt, out, TB90K) < 0) {
            av_packet_free(&out);
            return -1;
        }
    }
    av_packet_free(&out);
    return 0;
}

static int init_filters(rip_ctx *r, const AVFrame *f) {
    r->graph = avfilter_graph_alloc();
    char args[256];
    snprintf(args, sizeof args, "video_size=%dx%d:pix_fmt=%d:time_base=1/90000:pixel_aspect=%d/%d",
             f->width, f->height, f->format, r->sar.num, r->sar.den);
    if (avfilter_graph_create_filter(&r->bsrc, avfilter_get_by_name("buffer"), "in", args, NULL, r->graph) < 0 ||
        avfilter_graph_create_filter(&r->bsink, avfilter_get_by_name("buffersink"), "out", NULL, NULL, r->graph) < 0)
        return -1;
    // yadif with deint=interlaced only touches frames that are flagged interlaced, so progressive
    // (film) DVDs pass through untouched. The resolution is never changed (no upscaling).
    char desc[256];
    snprintf(desc, sizeof desc, "yadif=mode=send_frame:parity=auto:deint=interlaced,setsar=%d/%d,format=nv12",
             r->sar.num, r->sar.den);
    AVFilterInOut *outputs = avfilter_inout_alloc(), *inputs = avfilter_inout_alloc();
    outputs->name = av_strdup("in");
    outputs->filter_ctx = r->bsrc;
    inputs->name = av_strdup("out");
    inputs->filter_ctx = r->bsink;
    int rc = avfilter_graph_parse_ptr(r->graph, desc, &inputs, &outputs, NULL);
    avfilter_inout_free(&inputs);
    avfilter_inout_free(&outputs);
    if (rc < 0) return rc;
    return avfilter_graph_config(r->graph, NULL);
}

static int pull_filtered(rip_ctx *r) {
    AVFrame *ff = av_frame_alloc();
    int rc = 0;
    for (;;) {
        rc = av_buffersink_get_frame(r->bsink, ff);
        if (rc == AVERROR(EAGAIN) || rc == AVERROR_EOF) {
            rc = 0;
            break;
        }
        if (rc < 0) break;
        // yadif doubles the time base resolution (field rate); bring frames back to 90 kHz.
        AVRational stb = av_buffersink_get_time_base(r->bsink);
        if (ff->pts != AV_NOPTS_VALUE && (stb.num != 1 || stb.den != 90000))
            ff->pts = av_rescale_q(ff->pts, stb, TB90K);
        ff->duration = 0;
        rc = encode_video_frame(r, ff);
        av_frame_unref(ff);
        if (rc < 0) break;
    }
    av_frame_free(&ff);
    return rc;
}

static int decode_video(rip_ctx *r, AVPacket *pkt) {
    int rc = avcodec_send_packet(r->vdec, pkt);
    if (rc < 0 && rc != AVERROR_EOF && rc != AVERROR(EAGAIN)) {
        dr_logf(r->disc, 1, "MPEG-2 decode error (skipped)");
        return 0;
    }
    AVFrame *f = av_frame_alloc();
    for (;;) {
        rc = avcodec_receive_frame(r->vdec, f);
        if (rc == AVERROR(EAGAIN) || rc == AVERROR_EOF) {
            rc = 0;
            break;
        }
        if (rc < 0) {
            rc = 0;
            break;
        }
        f->pts = f->best_effort_timestamp;
        if (!r->graph && init_filters(r, f) < 0) {
            set_error(r, DR_ERR_ENCODER, "Cannot set up the video filters");
            rc = -1;
            break;
        }
        rc = av_buffersrc_add_frame_flags(r->bsrc, f, AV_BUFFERSRC_FLAG_KEEP_REF);
        av_frame_unref(f);
        if (rc < 0) break;
        if ((rc = pull_filtered(r)) < 0) break;
    }
    av_frame_free(&f);
    return rc;
}

static int flush_audio_fifo(rip_ctx *r, out_track *t, int final) {
    int fs = t->enc->frame_size > 0 ? t->enc->frame_size : 1024;
    AVPacket *out = av_packet_alloc();
    int rc = 0;
    while (av_audio_fifo_size(t->fifo) >= fs || (final && av_audio_fifo_size(t->fifo) > 0)) {
        int n = av_audio_fifo_size(t->fifo);
        if (n > fs) n = fs;
        AVFrame *f = av_frame_alloc();
        f->nb_samples = n;
        f->format = t->enc->sample_fmt;
        f->sample_rate = t->enc->sample_rate;
        av_channel_layout_copy(&f->ch_layout, &t->enc->ch_layout);
        av_frame_get_buffer(f, 0);
        av_audio_fifo_read(t->fifo, (void **)f->data, n);
        f->pts = t->fifo_pts;
        t->fifo_pts += n;
        rc = avcodec_send_frame(t->enc, f);
        av_frame_free(&f);
        if (rc < 0) break;
        while ((rc = avcodec_receive_packet(t->enc, out)) == 0) {
            if (write_packet(r, t, out, t->enc->time_base) < 0) {
                av_packet_free(&out);
                return -1;
            }
        }
        rc = 0;
    }
    if (final) {
        avcodec_send_frame(t->enc, NULL);
        while (avcodec_receive_packet(t->enc, out) == 0)
            if (write_packet(r, t, out, t->enc->time_base) < 0) break;
    }
    av_packet_free(&out);
    return rc;
}

static int handle_pcm(rip_ctx *r, out_track *t, AVStream *ist, AVPacket *pkt) {
    if (!t->dec) {
        const AVCodec *dec = avcodec_find_decoder(ist->codecpar->codec_id);
        t->dec = avcodec_alloc_context3(dec);
        avcodec_parameters_to_context(t->dec, ist->codecpar);
        t->dec->pkt_timebase = TB90K;
        if (avcodec_open2(t->dec, dec, NULL) < 0) {
            set_error(r, DR_ERR_ENCODER, "Cannot open the LPCM decoder");
            return -1;
        }
    }
    if (avcodec_send_packet(t->dec, pkt) < 0) return 0;
    AVFrame *f = av_frame_alloc();
    int rc = 0;
    while (avcodec_receive_frame(t->dec, f) == 0) {
        if (f->best_effort_timestamp != AV_NOPTS_VALUE && f->best_effort_timestamp < 0) {
            av_frame_unref(f);
            continue;
        }
        if (!t->fifo_pts_valid && f->best_effort_timestamp != AV_NOPTS_VALUE) {
            t->fifo_pts = av_rescale_q(f->best_effort_timestamp, TB90K, t->enc->time_base);
            t->fifo_pts_valid = 1;
        }
        if (f->ch_layout.nb_channels != t->enc->ch_layout.nb_channels || f->format != t->enc->sample_fmt ||
            f->sample_rate != t->enc->sample_rate) {
            if (!t->swr) {
                swr_alloc_set_opts2(&t->swr, &t->enc->ch_layout, t->enc->sample_fmt, t->enc->sample_rate,
                                    &f->ch_layout, f->format, f->sample_rate, 0, NULL);
                if (!t->swr || swr_init(t->swr) < 0) {
                    set_error(r, DR_ERR_ENCODER, "Cannot convert LPCM audio");
                    rc = -1;
                    break;
                }
            }
            AVFrame *c = av_frame_alloc();
            c->format = t->enc->sample_fmt;
            c->sample_rate = t->enc->sample_rate;
            av_channel_layout_copy(&c->ch_layout, &t->enc->ch_layout);
            if (swr_convert_frame(t->swr, c, f) == 0)
                av_audio_fifo_write(t->fifo, (void **)c->data, c->nb_samples);
            av_frame_free(&c);
        } else {
            av_audio_fifo_write(t->fifo, (void **)f->data, f->nb_samples);
        }
        av_frame_unref(f);
        if ((rc = flush_audio_fifo(r, t, 0)) < 0) break;
    }
    av_frame_free(&f);
    return rc;
}

static int handle_packet(rip_ctx *r, AVPacket *pkt) {
    AVFormatContext *ic = r->ic;
    if (pkt->stream_index >= r->stream_map_size) {
        int n = (int)ic->nb_streams;
        int *m = realloc(r->stream_map, (size_t)n * sizeof(int));
        if (!m) return -1;
        for (int i = r->stream_map_size; i < n; i++) m[i] = -2;
        r->stream_map = m;
        r->stream_map_size = n;
    }
    int *slot = &r->stream_map[pkt->stream_index];
    if (*slot == -2) {
        *slot = -1;
        int id = ic->streams[pkt->stream_index]->id;
        for (int i = 0; i < r->track_count; i++)
            if (r->tracks[i].ps_id == id) {
                *slot = i;
                break;
            }
    }
    if (*slot < 0) return 0;
    out_track *t = &r->tracks[*slot];
    AVStream *ist = ic->streams[pkt->stream_index];

    int64_t off = offset_for_packet(r, t, pkt);
    if (r->debug)
        fprintf(stderr, "pkt id=0x%x pos=%lld pts=%lld dts=%lld off=%lld\n", ist->id, (long long)pkt->pos,
                (long long)pkt->pts, (long long)pkt->dts, (long long)off);
    if (pkt->pts != AV_NOPTS_VALUE) pkt->pts += off;
    if (pkt->dts != AV_NOPTS_VALUE) pkt->dts += off;
    AVRational tb = ist->time_base;
    if (tb.num != 1 || tb.den != 90000) {
        av_packet_rescale_ts(pkt, tb, TB90K);
        tb = TB90K;
    }

    switch (t->kind) {
    case OUT_VIDEO:
        if (r->venc) return decode_video(r, pkt);
        if (pkt->dts == AV_NOPTS_VALUE) pkt->dts = t->last_dts == AV_NOPTS_VALUE ? pkt->pts : t->last_dts + 1;
        if (t->last_dts != AV_NOPTS_VALUE && pkt->dts <= t->last_dts) pkt->dts = t->last_dts + 1;
        if (pkt->pts != AV_NOPTS_VALUE && pkt->pts < pkt->dts) pkt->pts = pkt->dts;
        t->last_dts = pkt->dts;
        if (pkt->pts != AV_NOPTS_VALUE) r->media_pts90 = pkt->pts;
        return write_packet(r, t, pkt, tb) < 0 ? -1 : 0;
    case OUT_AUDIO_COPY: {
        int64_t ts = pkt->dts != AV_NOPTS_VALUE ? pkt->dts : pkt->pts;
        if (ts == AV_NOPTS_VALUE || ts < 0) return 0;
        if (t->last_dts != AV_NOPTS_VALUE && ts <= t->last_dts) return 0; // overlap at a seamless joint
        t->last_dts = ts;
        if (pkt->dts == AV_NOPTS_VALUE) pkt->dts = ts;
        if (pkt->pts == AV_NOPTS_VALUE) pkt->pts = ts;
        return write_packet(r, t, pkt, tb) < 0 ? -1 : 0;
    }
    case OUT_AUDIO_PCM:
        return handle_pcm(r, t, ist, pkt);
    case OUT_SUB: {
        if (pkt->pts == AV_NOPTS_VALUE || pkt->pts < 0) return 0;
        if (t->last_dts != AV_NOPTS_VALUE && pkt->pts <= t->last_dts) return 0;
        t->last_dts = pkt->pts;
        pkt->dts = pkt->pts;
        int64_t d = spu_duration90(pkt->data, pkt->size);
        if (d > 0) pkt->duration = d;
        return write_packet(r, t, pkt, tb) < 0 ? -1 : 0;
    }
    }
    return 0;
}

// ---------------------------------------------------------------------------------------------

static void cleanup(rip_ctx *r) {
    for (int i = 0; i < r->track_count; i++) {
        out_track *t = &r->tracks[i];
        avcodec_free_context(&t->dec);
        avcodec_free_context(&t->enc);
        swr_free(&t->swr);
        if (t->fifo) av_audio_fifo_free(t->fifo);
    }
    avcodec_free_context(&r->vdec);
    avcodec_free_context(&r->venc);
    avfilter_graph_free(&r->graph);
    if (r->ic) {
        AVIOContext *pb = r->ic->pb;
        avformat_close_input(&r->ic);
        if (pb) {
            av_freep(&pb->buffer);
            avio_context_free(&pb);
        }
    }
    if (r->oc) {
        if (r->oc->pb) avio_closep(&r->oc->pb);
        avformat_free_context(r->oc);
        r->oc = NULL;
    }
    if (r->file) DVDCloseFile(r->file);
    free(r->chunk);
    free(r->cell_byte_start);
    free(r->cell_offset90);
    free(r->cell_raw_start90);
    free(r->cell_offset_known);
    free(r->stream_map);
    dr_title_layout_free(&r->L);
}

int dr_rip(dr_disc *disc, const dr_rip_options *opt, dr_rip_result *res) {
    memset(res, 0, sizeof *res);
    rip_ctx R;
    memset(&R, 0, sizeof R);
    rip_ctx *r = &R;
    r->disc = disc;
    r->opt = opt;
    r->res = res;
    r->t0 = now_seconds();
    r->video_in_index = -1;
    r->is_device = strncmp(disc->path, "/dev/", 5) == 0;
    r->debug = getenv("DISCRIP_DEBUG") != NULL;
    av_log_set_level(AV_LOG_ERROR);

    if (dr_title_layout_build(disc, opt->title, opt->angle, &r->L) != 0) {
        set_error(r, DR_ERR_OPEN_TITLE, "Title %d has no playable cells", opt->title);
        cleanup(r);
        return res->code;
    }
    if (disc->css_failed_vts[r->L.vts]) {
        set_error(r, DR_ERR_CSS_KEY, "The CSS key for title %d (VTS %d) could not be found", opt->title, r->L.vts);
        cleanup(r);
        return res->code;
    }
    r->file = DVDOpenFile(disc->dvd, r->L.vts, DVD_READ_TITLE_VOBS);
    if (!r->file) {
        set_error(r, DR_ERR_OPEN_TITLE, "Cannot open the VOB files of title set %d", r->L.vts);
        cleanup(r);
        return res->code;
    }
    if (disc->css_failed_vts[r->L.vts]) {
        set_error(r, DR_ERR_CSS_KEY, "The CSS key for title %d (VTS %d) could not be found", opt->title, r->L.vts);
        cleanup(r);
        return res->code;
    }
    r->chunk = malloc(CHUNK_BLOCKS * 2048);
    r->cell_byte_start = malloc(sizeof(int64_t) * (size_t)r->L.cell_count);
    r->cell_offset90 = calloc((size_t)r->L.cell_count, sizeof(int64_t));
    r->cell_raw_start90 = calloc((size_t)r->L.cell_count, sizeof(int64_t));
    r->cell_offset_known = calloc((size_t)r->L.cell_count, sizeof(int));
    for (int i = 0; i < r->L.cell_count; i++) r->cell_byte_start[i] = INT64_MAX;

    // Demuxer on top of the block reader.
    uint8_t *iobuf = av_malloc(AVIO_BUFFER);
    AVIOContext *avio = avio_alloc_context(iobuf, AVIO_BUFFER, 0, r, avio_read_cb, NULL, NULL);
    r->ic = avformat_alloc_context();
    r->ic->pb = avio;
    r->ic->probesize = 32 * 1024 * 1024;
    r->ic->max_analyze_duration = 30 * (int64_t)AV_TIME_BASE;
    r->ic->interrupt_callback.callback = interrupt_cb;
    r->ic->interrupt_callback.opaque = r;
    const AVInputFormat *ps = av_find_input_format("mpeg");
    int rc = avformat_open_input(&r->ic, NULL, ps, NULL);
    if (rc >= 0) rc = avformat_find_stream_info(r->ic, NULL);
    if (rc < 0 || r->reader_error) {
        if (cancelled(r)) {
            res->code = DR_CANCELLED;
            snprintf(res->message, sizeof res->message, "Cancelled");
        } else if (r->reader_error == DR_ERR_READ_REFUSED) {
            set_error(r, DR_ERR_READ_REFUSED, "The drive refuses to read title %d", opt->title);
        } else if (r->reader_error) {
            set_error(r, r->reader_error, "Reading the disc failed");
        } else {
            set_error(r, DR_ERR_DEMUX, "Cannot parse the MPEG program stream of title %d", opt->title);
        }
        cleanup(r);
        return res->code;
    }

    AVStream *vin = find_input(r, 0x1e0);
    if (!vin) {
        set_error(r, DR_ERR_DEMUX, "Title %d contains no MPEG video stream", opt->title);
        cleanup(r);
        return res->code;
    }

    if (avformat_alloc_output_context2(&r->oc, NULL, "matroska", opt->output_path) < 0) {
        set_error(r, DR_ERR_INTERNAL, "Cannot create the Matroska muxer");
        cleanup(r);
        return res->code;
    }
    if (opt->global_tags)
        for (const char *const *kv = opt->global_tags; kv[0] && kv[1]; kv += 2)
            av_dict_set(&r->oc->metadata, kv[0], kv[1], 0);

    if (setup_video(r, vin) < 0) goto fail;
    for (int i = 0; i < opt->audio_count; i++)
        if (setup_audio(r, opt->audio_stream_ids[i], opt->audio_languages ? opt->audio_languages[i] : NULL,
                        opt->audio_names ? opt->audio_names[i] : NULL, i == opt->default_audio_index) < 0)
            goto fail;
    for (int i = 0; i < opt->subtitle_count; i++)
        if (setup_subtitle(r, opt->subtitle_stream_ids[i],
                           opt->subtitle_languages ? opt->subtitle_languages[i] : NULL,
                           opt->subtitle_names ? opt->subtitle_names[i] : NULL,
                           vin->codecpar->width, vin->codecpar->height) < 0)
            goto fail;
    if (setup_chapters(r) < 0) goto fail;

    rc = avio_open(&r->oc->pb, opt->output_path, AVIO_FLAG_WRITE);
    if (rc < 0) {
        char err[128];
        av_strerror(rc, err, sizeof err);
        set_error(r, map_errno(rc), "Cannot create %s: %s", opt->output_path, err);
        goto fail;
    }
    rc = avformat_write_header(r->oc, NULL);
    if (rc < 0) {
        char err[128];
        av_strerror(rc, err, sizeof err);
        set_error(r, map_errno(rc), "Cannot write the MKV header: %s", err);
        goto fail;
    }

    AVPacket *pkt = av_packet_alloc();
    for (;;) {
        if (cancelled(r)) break;
        rc = av_read_frame(r->ic, pkt);
        if (rc < 0) break;
        int hr = handle_packet(r, pkt);
        av_packet_unref(pkt);
        if (hr < 0) break;
        report(r, 0);
    }
    av_packet_free(&pkt);

    if (cancelled(r)) {
        res->code = DR_CANCELLED;
        snprintf(res->message, sizeof res->message, "Cancelled");
        goto fail;
    }
    if (res->code != DR_OK) goto fail;
    if (r->reader_error) {
        set_error(r, r->reader_error,
                  r->reader_error == DR_ERR_DEVICE_GONE ? "The drive was disconnected or the disc was ejected"
                  : r->reader_error == DR_ERR_READ_REFUSED ? "The drive refuses to read this title"
                                                           : "Too many unreadable sectors in a row");
        goto fail;
    }

    // Flush the lossy chain and the audio encoders.
    if (r->venc) {
        decode_video(r, NULL);
        if (r->graph) {
            if (av_buffersrc_add_frame(r->bsrc, NULL) < 0) dr_logf(r->disc, 1, "video filter flush failed");
            pull_filtered(r);
        }
        encode_video_frame(r, NULL);
        if (res->code != DR_OK) goto fail;
    }
    for (int i = 0; i < r->track_count; i++)
        if (r->tracks[i].kind == OUT_AUDIO_PCM && r->tracks[i].enc) flush_audio_fifo(r, &r->tracks[i], 1);
    if (res->code != DR_OK) goto fail;

    report(r, 2);
    rc = av_write_trailer(r->oc);
    if (rc < 0) {
        char err[128];
        av_strerror(rc, err, sizeof err);
        set_error(r, map_errno(rc), "Finishing the MKV failed: %s", err);
        goto fail;
    }
    res->output_bytes = avio_size(r->oc->pb);
    res->media_seconds = r->media_pts90 / 90000.0;
    report(r, 2);
    cleanup(r);
    return DR_OK;

fail:
    if (res->code == DR_OK) set_error(r, DR_ERR_INTERNAL, "The rip failed");
    {
        int had_output = r->oc && r->oc->pb;
        cleanup(r);
        if (had_output && unlink(opt->output_path) == 0) res->partial_file_removed = 1;
    }
    return res->code;
}
