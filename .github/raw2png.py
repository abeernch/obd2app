"""Turns a raw `adb exec-out screencap` frame into a PNG: python3 raw2png.py IN OUT

Raw frames are captured because encoding a PNG on the emulator itself takes seconds per frame, which
leaves too few frames to judge an animation. The raw header is width, height and pixel format (and on
newer Android a colour space), each a little-endian 32-bit number, followed by 4 bytes per pixel.
"""
import struct
import sys
import zlib


def main(src, dst):
    data = open(src, 'rb').read()
    w, h, fmt = struct.unpack('<III', data[:12])
    pixels = data[len(data) - w * h * 4:]
    # 1 RGBA, 2 RGBX, 5 BGRA. Written out as RGB: the screen has no transparency.
    rgb = bytearray(w * h * 3)
    if fmt == 5:
        rgb[0::3], rgb[1::3], rgb[2::3] = pixels[2::4], pixels[1::4], pixels[0::4]
    else:
        rgb[0::3], rgb[1::3], rgb[2::3] = pixels[0::4], pixels[1::4], pixels[2::4]
    stride = w * 3
    rows = b''.join(b'\x00' + bytes(rgb[y * stride:(y + 1) * stride]) for y in range(h))

    def chunk(kind, body):
        return struct.pack('>I', len(body)) + kind + body + struct.pack('>I', zlib.crc32(kind + body) & 0xFFFFFFFF)

    png = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 2, 0, 0, 0))
    png += chunk(b'IDAT', zlib.compress(rows, 3)) + chunk(b'IEND', b'')
    open(dst, 'wb').write(png)


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
