import { Hct, DynamicScheme, TonalPalette, MaterialDynamicColors, argbFromHex, hexFromArgb } from '@material/material-color-utilities';
import { Variant } from './node_modules/@material/material-color-utilities/dynamiccolor/variant.js';
const seed = Hct.fromInt(argbFromHex('#4F5BD5'));
const ter = Hct.fromInt(argbFromHex('#E8873A'));
const roles = ['primary','onPrimary','primaryContainer','onPrimaryContainer','inversePrimary','secondary','onSecondary','secondaryContainer','onSecondaryContainer','tertiary','onTertiary','tertiaryContainer','onTertiaryContainer','background','onBackground','surface','onSurface','surfaceVariant','onSurfaceVariant','surfaceTint','inverseSurface','inverseOnSurface','error','onError','errorContainer','onErrorContainer','outline','outlineVariant','scrim','surfaceBright','surfaceContainer','surfaceContainerHigh','surfaceContainerHighest','surfaceContainerLow','surfaceContainerLowest','surfaceDim'];
const out = {};
for (const dark of [false,true]) {
  const s = new DynamicScheme({sourceColorArgb: seed.toInt(), variant: Variant.TONAL_SPOT, contrastLevel: 0, isDark: dark,
    primaryPalette: TonalPalette.fromHueAndChroma(seed.hue, 56),
    secondaryPalette: TonalPalette.fromHueAndChroma(seed.hue, 18),
    tertiaryPalette: TonalPalette.fromHueAndChroma(ter.hue, 48),
    neutralPalette: TonalPalette.fromHueAndChroma(seed.hue, 5),
    neutralVariantPalette: TonalPalette.fromHueAndChroma(seed.hue, 9)});
  out[dark?'dark':'light'] = Object.fromEntries(roles.map(r => [r, hexFromArgb(MaterialDynamicColors[r].getArgb(s)).toUpperCase()]));
}
console.log(JSON.stringify(out));
