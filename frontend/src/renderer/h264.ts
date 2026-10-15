// H.264 helpers shared by every view that decodes a phone's video.

/**
 * The `avc1.PPCCLL` string for an Annex-B stream, read out of its first SPS.
 *
 * `VideoDecoder.configure` needs a codec string, and profile and level differ
 * between phones: a guess that disagrees with the bytes configures cleanly and
 * then decodes nothing, which looks exactly like a camera that is not sending.
 * The three bytes after the SPS header are profile_idc, the constraint flags
 * and level_idc, which is precisely what the string encodes.
 */
export const codecFromAnnexB = (data: Uint8Array): string | null => {
  for (let i = 0; i + 5 < data.length; i += 1) {
    if (data[i] !== 0 || data[i + 1] !== 0) continue;
    const start = data[i + 2] === 1 ? i + 3 : data[i + 2] === 0 && data[i + 3] === 1 ? i + 4 : -1;
    if (start < 0) continue;
    // NAL type 7 is the sequence parameter set.
    if ((data[start] & 0x1f) === 7 && start + 3 < data.length) {
      const hex = [data[start + 1], data[start + 2], data[start + 3]]
        .map((byte) => byte.toString(16).padStart(2, '0'))
        .join('');
      return `avc1.${hex}`;
    }
    i = start - 1;
  }
  return null;
};
