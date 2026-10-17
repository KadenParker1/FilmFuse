import { useState } from 'react';
import { useUpdateComfortSettings } from '../../api/comfort';
import type { ComfortSettingsDTO, OnboardingStateDTO } from '../../types/api';
import { ComfortFields } from '../comfort/ComfortFields';
import { GROUP_PRIVACY_NOTE, limitsForCertification, validateComfort } from '../comfort/comfortCopy';
import { Body } from '../ui';
import { OnboardingFrame } from './OnboardingFrame';
import { describeError } from './steps';
import { useAdvance } from './useAdvance';

/** Never send a hidden row's value (e.g. "Sex & nudity" below R). */
const saveable = (d: ComfortSettingsDTO): ComfortSettingsDTO => ({
  ...d,
  contentLimits: limitsForCertification(d.maxCertification, d.contentLimits),
});

/** Step 6: content comfort (the same pickers as Settings → Comfort), saved via PUT /me/settings/comfort. */
export function ComfortStep({ state }: { state: OnboardingStateDTO }) {
  const update = useUpdateComfortSettings();
  const nav = useAdvance(6);
  const [draft, setDraft] = useState<ComfortSettingsDTO>(state.comfort);
  const [localError, setLocalError] = useState<string | null>(null);

  const edit = (patch: Partial<ComfortSettingsDTO>) => {
    setLocalError(null);
    update.reset();
    setDraft((d) => ({ ...d, ...patch }));
  };

  async function next() {
    nav.reset();
    const problem = validateComfort(draft);
    if (problem) {
      setLocalError(problem);
      return;
    }
    try {
      await update.mutateAsync(saveable(draft));
    } catch {
      return;
    }
    await nav.advance();
  }

  return (
    <OnboardingFrame
      step={6}
      title="Comfort settings"
      subtitle="Hard limits, not preferences: we never suggest anything above them. Change them any time in Settings."
      onBack={nav.back}
      onContinue={() => void next()}
      loading={update.isPending || nav.isPending}
      error={localError ?? describeError(update.error ?? nav.error)}
    >
      <ComfortFields value={draft} onChange={edit} />
      <Body muted className="text-sm">
        {GROUP_PRIVACY_NOTE}
      </Body>
    </OnboardingFrame>
  );
}
