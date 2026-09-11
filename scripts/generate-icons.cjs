const { app, BrowserWindow, nativeImage } = require('electron');
const fs = require('fs');
const path = require('path');

app.commandLine.appendSwitch('disable-gpu');
app.commandLine.appendSwitch('disable-software-rasterizer');

function createIco(pngBuffers) {
  const count = pngBuffers.length;
  const header = Buffer.alloc(6);
  header.writeUInt16LE(0, 0); // reserved
  header.writeUInt16LE(1, 2); // 1 = ICO
  header.writeUInt16LE(count, 4);

  let offset = 6 + count * 16;
  const dirEntries = [];

  for (const img of pngBuffers) {
    const entry = Buffer.alloc(16);
    entry.writeUInt8(img.width >= 256 ? 0 : img.width, 0);
    entry.writeUInt8(img.height >= 256 ? 0 : img.height, 1);
    entry.writeUInt8(0, 2); // color palette
    entry.writeUInt8(0, 3); // reserved
    entry.writeUInt16LE(1, 4); // color planes
    entry.writeUInt16LE(32, 6); // bpp
    entry.writeUInt32LE(img.buffer.length, 8);
    entry.writeUInt32LE(offset, 12);
    offset += img.buffer.length;
    dirEntries.push(entry);
  }

  return Buffer.concat([header, ...dirEntries, ...pngBuffers.map((p) => p.buffer)]);
}

app.whenReady().then(async () => {
  const win = new BrowserWindow({
    width: 512,
    height: 512,
    show: false,
    frame: false,
    transparent: true,
    backgroundColor: '#00000000',
    webPreferences: {
      offscreen: true
    }
  });

  // High-contrast, bold Switchboard controller artwork
  const svgInner = `
    <!-- Chassis Shell (Rich container + crisp luminous border) -->
    <path d="M5.67412 3.77772C5 4.78661 5 6.19108 5 9V15C5 17.8089 5 19.2134 5.67412 20.2223C5.96596 20.659 6.34096 21.034 6.77772 21.3259C7.78661 22 9.19108 22 12 22C14.8089 22 16.2134 22 17.2223 21.3259C17.659 21.034 18.034 20.659 18.3259 20.2223C19 19.2134 19 17.8089 19 15V9C19 6.19108 19 4.78661 18.3259 3.77772C18.034 3.34096 17.659 2.96596 17.2223 2.67412C16.2134 2 14.8089 2 12 2C9.19108 2 7.78661 2 6.77772 2.67412C6.34096 2.96596 5.96596 3.34096 5.67412 3.77772Z"
          fill="#222538" stroke="#7AA2F7" stroke-width="0.7" stroke-opacity="0.6"/>
    <!-- Top Active Indicator / Status Bar -->
    <path d="M9 4.75C8.58579 4.75 8.25 5.08579 8.25 5.5C8.25 5.91421 8.58579 6.25 9 6.25H15C15.4142 6.25 15.75 5.91421 15.75 5.5C15.75 5.08579 15.4142 4.75 15 4.75H9Z"
          fill="#9ECE6A"/>
    <!-- Left Action Button -->
    <path d="M10 9C10 9.55228 9.55228 10 9 10C8.44772 10 8 9.55228 8 9C8 8.44772 8.44772 8 9 8C9.55228 8 10 8.44772 10 9Z"
          fill="#C8D1F0"/>
    <!-- Center Status / Select Button -->
    <path d="M12 10C12.5523 10 13 9.55228 13 9C13 8.44772 12.5523 8 12 8C11.4477 8 11 8.44772 11 9C11 9.55228 11.4477 10 12 10Z"
          fill="#9ECE6A"/>
    <!-- Right Action Button -->
    <path d="M16 9C16 9.55228 15.5523 10 15 10C14.4477 10 14 9.55228 14 9C14 8.44772 14 8.44772 14 9C14 8.44772 14.4477 8 15 8C15.5523 8 16 8.44772 16 9Z"
          fill="#C8D1F0"/>
    <!-- Jog Dial Controller -->
    <path fill-rule="evenodd" clip-rule="evenodd"
          d="M8.25 15.5C8.25 13.4289 9.92893 11.75 12 11.75C14.0711 11.75 15.75 13.4289 15.75 15.5C15.75 17.5711 14.0711 19.25 12 19.25C9.92893 19.25 8.25 17.5711 8.25 15.5ZM9.75 15.5C9.75 14.2574 10.7574 13.25 12 13.25C13.2426 13.25 14.25 14.2574 14.25 15.5C14.25 16.7426 13.2426 17.75 12 17.75C10.7574 17.75 9.75 16.7426 9.75 15.5Z"
          fill="#7AA2F7"/>
  `;

  // Desktop App Icon: Full-bleed premium squircle with crisp transparent background
  const desktopSvg = `
    <svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="0 0 512 512">
      <defs>
        <linearGradient id="bgGrad" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stop-color="#1E2030"/>
          <stop offset="100%" stop-color="#12131A"/>
        </linearGradient>
      </defs>
      <!-- Squircle Canvas (480x480 centered with transparent corners) -->
      <rect x="16" y="16" width="480" height="480" rx="108" ry="108" fill="url(#bgGrad)" stroke="#394264" stroke-width="4"/>
      <rect x="18" y="18" width="476" height="476" rx="106" ry="106" fill="none" stroke="#7AA2F7" stroke-width="1.5" stroke-opacity="0.3"/>
      <!-- Prominent, Centered Switchboard Controller (scale 18x = 360px tall) -->
      <g transform="translate(40, 40) scale(18)">
        ${svgInner}
      </g>
    </svg>
  `;

  // Tray Icon: a glyph, not the app icon.
  //
  // The tray used to be the desktop icon downscaled to 32px, which put an
  // opaque gradient plate and a border in a row of flat transparent glyphs --
  // it read as a solid blue tile, and by 16px the dial and the three buttons
  // had collapsed into mush. A notification-area icon gets ~16 device pixels,
  // so this drops the plate for a transparent ground and keeps only the two
  // shapes that survive at that size: the chassis outline and the jog dial.
  // Strokes are heavy because a hairline disappears entirely once Windows
  // scales it down.
  const traySvg = `
    <svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="0 0 24 24">
      <path d="M5.67412 3.77772C5 4.78661 5 6.19108 5 9V15C5 17.8089 5 19.2134 5.67412 20.2223C5.96596 20.659 6.34096 21.034 6.77772 21.3259C7.78661 22 9.19108 22 12 22C14.8089 22 16.2134 22 17.2223 21.3259C17.659 21.034 18.034 20.659 18.3259 20.2223C19 19.2134 19 17.8089 19 15V9C19 6.19108 19 4.78661 18.3259 3.77772C18.034 3.34096 17.659 2.96596 17.2223 2.67412C16.2134 2 14.8089 2 12 2C9.19108 2 7.78661 2 6.77772 2.67412C6.34096 2.96596 5.96596 3.34096 5.67412 3.77772Z"
            fill="none" stroke="#C8D1F0" stroke-width="2.1"/>
      <circle cx="12" cy="14.75" r="3.15" fill="none" stroke="#C8D1F0" stroke-width="2.1"/>
      <path d="M9 5.5H15" stroke="#C8D1F0" stroke-width="2.1" stroke-linecap="round"/>
    </svg>
  `;

  // Android Round Icon (512x512 circle on transparent canvas)
  const androidRoundSvg = `
    <svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="0 0 512 512">
      <circle cx="256" cy="256" r="248" fill="#16161E" stroke="#394264" stroke-width="4"/>
      <g transform="translate(68, 68) scale(15.666)">
        ${svgInner}
      </g>
    </svg>
  `;

  // Android Square / Legacy Icon (512x512 squircle on transparent canvas)
  const androidSquareSvg = `
    <svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="0 0 512 512">
      <rect x="16" y="16" width="480" height="480" rx="108" ry="108" fill="#16161E" stroke="#394264" stroke-width="4"/>
      <g transform="translate(68, 68) scale(15.666)">
        ${svgInner}
      </g>
    </svg>
  `;

  async function renderSvgToImage(svgStr) {
    const html = `<!DOCTYPE html><html><head><meta charset="utf-8"><style>
      * { margin: 0; padding: 0; box-sizing: border-box; }
      html, body { width: 512px; height: 512px; background: transparent !important; overflow: hidden; }
      svg { display: block; width: 512px; height: 512px; }
    </style></head><body>${svgStr}</body></html>`;
    const dataUri = `data:text/html;charset=utf-8,${encodeURIComponent(html)}`;
    await win.loadURL(dataUri);
    // Wait briefly for layout
    await new Promise((r) => setTimeout(r, 100));
    return await win.webContents.capturePage({ x: 0, y: 0, width: 512, height: 512 });
  }

  console.log('Rendering high-contrast transparent desktop icon...');
  const desktopImg = await renderSvgToImage(desktopSvg);

  console.log('Rendering tray glyph...');
  const trayImg = await renderSvgToImage(traySvg);

  console.log('Rendering android round icon...');
  const androidRoundImg = await renderSvgToImage(androidRoundSvg);

  console.log('Rendering android square icon...');
  const androidSquareImg = await renderSvgToImage(androidSquareSvg);

  const rootDir = path.resolve(__dirname, '..');
  const desktopResourcesDir = path.join(rootDir, 'frontend', 'resources');
  const desktopAssetsDir = path.join(rootDir, 'frontend', 'src', 'renderer', 'assets');
  const desktopPublicDir = path.join(rootDir, 'frontend', 'src', 'renderer', 'public');
  const desktopDistAssetsDir = path.join(rootDir, 'frontend', 'dist', 'renderer', 'assets');
  const desktopDistDir = path.join(rootDir, 'frontend', 'dist', 'renderer');
  const mobileResDir = path.join(rootDir, 'mobile', 'app', 'src', 'main', 'res');

  [desktopResourcesDir, desktopAssetsDir, desktopPublicDir, desktopDistAssetsDir, desktopDistDir].forEach((d) => {
    fs.mkdirSync(d, { recursive: true });
  });

  // Verify corner pixel transparency
  const rawBitmap = desktopImg.toBitmap();
  console.log('Corner (0,0) BGRA:', rawBitmap[0], rawBitmap[1], rawBitmap[2], rawBitmap[3]);
  if (rawBitmap[3] !== 0) {
    console.warn('WARNING: Corner alpha is not zero! Alpha:', rawBitmap[3]);
  } else {
    console.log('Corner transparency verified: Alpha is 0 (clean transparent background).');
  }

  // Save Desktop PNGs
  const desktop512 = desktopImg.resize({ width: 512, height: 512, quality: 'best' }).toPNG();
  const desktop256 = desktopImg.resize({ width: 256, height: 256, quality: 'best' }).toPNG();
  const desktop128 = desktopImg.resize({ width: 128, height: 128, quality: 'best' }).toPNG();
  const desktop64 = desktopImg.resize({ width: 64, height: 64, quality: 'best' }).toPNG();
  const desktop48 = desktopImg.resize({ width: 48, height: 48, quality: 'best' }).toPNG();
  const desktop32 = desktopImg.resize({ width: 32, height: 32, quality: 'best' }).toPNG();
  const desktop24 = desktopImg.resize({ width: 24, height: 24, quality: 'best' }).toPNG();
  const desktop16 = desktopImg.resize({ width: 16, height: 16, quality: 'best' }).toPNG();

  // Multi-size ICO for Windows
  const icoBuffer = createIco([
    { width: 256, height: 256, buffer: desktop256 },
    { width: 128, height: 128, buffer: desktop128 },
    { width: 64, height: 64, buffer: desktop64 },
    { width: 48, height: 48, buffer: desktop48 },
    { width: 32, height: 32, buffer: desktop32 },
    { width: 24, height: 24, buffer: desktop24 },
    { width: 16, height: 16, buffer: desktop16 }
  ]);

  // Write to all target locations
  [desktopResourcesDir, desktopAssetsDir, desktopPublicDir, desktopDistDir].forEach((dir) => {
    fs.writeFileSync(path.join(dir, 'icon.png'), desktop512);
    fs.writeFileSync(path.join(dir, 'icon.ico'), icoBuffer);
    if (dir === desktopPublicDir || dir === desktopDistDir) {
      fs.writeFileSync(path.join(dir, 'favicon.ico'), icoBuffer);
    }
  });
  fs.writeFileSync(path.join(desktopDistAssetsDir, 'icon.png'), desktop512);
  fs.writeFileSync(path.join(desktopDistAssetsDir, 'icon.ico'), icoBuffer);

  // Tray icon. The ICO carries every size the notification area asks for
  // across DPI settings (16 at 100%, 20 at 125%, 24 at 150%, 32 at 200%), so
  // the shell picks one rather than downscaling a single 32px bitmap. The PNG
  // stays for non-Windows hosts and as the fallback.
  const trayIco = createIco(
    [16, 20, 24, 32].map((size) => ({
      width: size,
      height: size,
      buffer: trayImg.resize({ width: size, height: size, quality: 'best' }).toPNG()
    }))
  );
  const trayPng = trayImg.resize({ width: 32, height: 32, quality: 'best' }).toPNG();
  [desktopResourcesDir, desktopAssetsDir, desktopDistAssetsDir].forEach((dir) => {
    fs.writeFileSync(path.join(dir, 'tray.png'), trayPng);
    fs.writeFileSync(path.join(dir, 'tray.ico'), trayIco);
  });

  // Android mipmaps
  const densities = [
    { dir: 'mipmap-mdpi', size: 48 },
    { dir: 'mipmap-hdpi', size: 72 },
    { dir: 'mipmap-xhdpi', size: 96 },
    { dir: 'mipmap-xxhdpi', size: 144 },
    { dir: 'mipmap-xxxhdpi', size: 192 }
  ];

  for (const { dir, size } of densities) {
    const targetDir = path.join(mobileResDir, dir);
    fs.mkdirSync(targetDir, { recursive: true });

    const squarePng = androidSquareImg.resize({ width: size, height: size, quality: 'best' }).toPNG();
    const roundPng = androidRoundImg.resize({ width: size, height: size, quality: 'best' }).toPNG();

    fs.writeFileSync(path.join(targetDir, 'ic_launcher.png'), squarePng);
    fs.writeFileSync(path.join(targetDir, 'ic_launcher_round.png'), roundPng);
    console.log(`Generated ${dir}/ic_launcher.png and ic_launcher_round.png (${size}x${size})`);
  }

  console.log('All icons regenerated successfully with transparent background and high quality!');
  app.quit();
});
