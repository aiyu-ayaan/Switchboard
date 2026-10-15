import { useEffect, useRef } from 'react';
import { PhoneMirror, PhoneNavBar } from './PhoneMirror';
import { useMirrorState } from './PhoneView';

/**
 * One phone app in a window of its own, or the whole phone when no package
 * is given.
 *
 * Every window shows the phone's single screen, so what makes a window "the
 * WhatsApp window" is that focusing it brings WhatsApp to the front on the
 * phone. While another app is in front, the window says so instead of quietly
 * showing someone else's content under its title.
 */
export const PhoneWindow = () => {
  const params = new URLSearchParams(window.location.hash.split('?')[1] ?? '');
  const pkg = params.get('pkg') ?? '';
  const label = params.get('label') || 'Phone';
  const { mirror } = useMirrorState(700);
  const lastLaunch = useRef(0);

  const live = Boolean(mirror?.streaming);
  const elsewhere = Boolean(pkg && mirror?.foreground && mirror.foreground !== pkg);

  useEffect(() => {
    document.title = label;
  }, [label]);

  // Focus is the switch. Throttled, because a window gains focus several
  // times in a burst when it is clicked from another one.
  useEffect(() => {
    if (!pkg) return;
    const bringToFront = () => {
      const now = Date.now();
      if (now - lastLaunch.current < 600) return;
      lastLaunch.current = now;
      void window.switchboard.phone.launch(pkg).catch(() => undefined);
    };
    window.addEventListener('focus', bringToFront);
    return () => window.removeEventListener('focus', bringToFront);
  }, [pkg]);

  return (
    <div className="flex h-full flex-col bg-[#11121a]">
      <div className="relative flex min-h-0 flex-1 items-center justify-center p-2">
        <PhoneMirror live={live} control={Boolean(mirror?.control)} />
        {elsewhere && live && (
          <button
            type="button"
            onClick={() => window.switchboard.phone.launch(pkg)}
            className="absolute inset-2 flex items-center justify-center rounded-[18px] bg-black/70 px-6 text-center text-tiny text-ink backdrop-blur-[2px]"
          >
            Another app is in front on the phone. Click to bring {label} back.
          </button>
        )}
        {!live && (
          <p className="absolute px-6 text-center text-tiny text-ink-faint">
            {mirror?.awaitingConsent
              ? 'Allow sharing on the phone: Android is asking on its screen.'
              : 'The phone is not sharing its screen. Start it from the Phone view in Switchboard.'}
          </p>
        )}
      </div>
      <div className="border-t border-edge py-1">
        <PhoneNavBar disabled={!live || !mirror?.control} />
      </div>
    </div>
  );
};
