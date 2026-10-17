import React from 'react';
import { ComfortFields } from '../components/comfort/ComfortFields';
import { limitsForCertification, showsSexualRow } from '../components/comfort/comfortCopy';
import type { ComfortSettingsDTO } from '../types/api';
import { useState } from 'react';
import { fireEvent, render, screen } from '@testing-library/react-native';
import { CertificationPicker } from '../components/comfort/CertificationPicker';
import { ContentLevelPicker, ContentLimitsEditor } from '../components/comfort/ContentLevelPicker';
import {
  CERTIFICATIONS,
  CONTENT_DIMENSIONS,
  LEVEL_EXAMPLES,
  certificationDescription,
  certificationLabel,
  levelFor,
  sameLimits,
  validateComfort,
  withLevel,
} from '../components/comfort/comfortCopy';
import { FilterBanner, filterBannerText, isUnfiltered } from '../components/comfort/FilterBanner';
import { ChoiceGroup, ConfirmSheet } from '../components/ui';
import { NetworkError } from '../lib/apiClient';
import { makeComfort, makeEffectiveFilter } from '../test-utils/fixtures';
import type { Certification, ContentDimension, ContentLevel } from '../types/api';

describe('comfortCopy', () => {
  it('labels certifications for display', () => {
    expect(CERTIFICATIONS.map(certificationLabel)).toEqual(['G', 'PG', 'PG-13', 'R', 'NC-17']);
  });

  it('describes every choice, including no limit', () => {
    expect(certificationDescription(null)).toMatch(/Any rating/);
    for (const c of CERTIFICATIONS) expect(certificationDescription(c).length).toBeGreaterThan(10);
  });

  it('has an example for every dimension × level', () => {
    for (const d of CONTENT_DIMENSIONS) {
      expect(Object.keys(LEVEL_EXAMPLES[d])).toEqual(['NONE', 'MILD', 'MODERATE', 'STRONG']);
    }
  });

  it('treats a missing dimension as STRONG and stores STRONG by omission', () => {
    expect(levelFor({}, 'VIOLENCE')).toBe('STRONG');
    expect(levelFor({ VIOLENCE: 'MILD' }, 'VIOLENCE')).toBe('MILD');
    expect(withLevel({}, 'LANGUAGE', 'NONE')).toEqual({ LANGUAGE: 'NONE' });
    expect(withLevel({ LANGUAGE: 'NONE', DRUGS: 'MILD' }, 'LANGUAGE', 'STRONG')).toEqual({ DRUGS: 'MILD' });
  });

  it('compares limits with missing ≡ STRONG', () => {
    expect(sameLimits({}, { VIOLENCE: 'STRONG' })).toBe(true);
    expect(sameLimits({}, { VIOLENCE: 'MILD' })).toBe(false);
  });

  it('validates certification and limit values', () => {
    expect(validateComfort(makeComfort())).toBeNull();
    expect(validateComfort(makeComfort({ maxCertification: 'PG_13', contentLimits: { SEXUAL: 'NONE' } }))).toBeNull();
    expect(validateComfort(makeComfort({ maxCertification: 'X' as Certification }))).toMatch(/rating/);
    expect(
      validateComfort(makeComfort({ contentLimits: { VIOLENCE: 'EXTREME' as ContentLevel } })),
    ).toMatch(/content limits/);
    expect(
      validateComfort(makeComfort({ contentLimits: { GORE: 'MILD' } as unknown as Record<ContentDimension, ContentLevel> })),
    ).toMatch(/content limits/);
  });
});

describe('ChoiceGroup', () => {
  it('is a radio group; pressing another choice reports it, the selected one does nothing', async () => {
    const onChange = jest.fn();
    await render(
      <ChoiceGroup
        label="Size"
        choices={[
          { value: 'S', label: 'Small' },
          { value: 'L', label: 'Large', accessibilityLabel: 'Large size' },
        ]}
        value="S"
        onChange={onChange}
      />,
    );
    expect(screen.getByLabelText('Size').props.accessibilityRole).toBe('radiogroup');
    expect(screen.getByRole('radio', { name: 'Small' })).toBeChecked();
    expect(screen.getByRole('radio', { name: 'Large size' })).not.toBeChecked();
    await fireEvent.press(screen.getByRole('radio', { name: 'Small' }));
    expect(onChange).not.toHaveBeenCalled();
    await fireEvent.press(screen.getByRole('radio', { name: 'Large size' }));
    expect(onChange).toHaveBeenCalledWith('L');
  });

  it('can be disabled', async () => {
    const onChange = jest.fn();
    await render(
      <ChoiceGroup label="X" choices={[{ value: 1, label: 'One' }, { value: 2, label: 'Two' }]} value={1} onChange={onChange} disabled />,
    );
    expect(screen.getByRole('radio', { name: 'Two' })).toBeDisabled();
  });
});

describe('CertificationPicker', () => {
  function Harness({ initial = null as Certification | null }) {
    const [value, setValue] = useState<Certification | null>(initial);
    return <CertificationPicker title="Highest rating" value={value} onChange={setValue} />;
  }

  it('offers No limit plus every rating and describes the choice', async () => {
    await render(<Harness />);
    expect(screen.getByLabelText('Highest rating').props.accessibilityRole).toBe('radiogroup');
    expect(screen.getAllByRole('radio').map((r) => r.props.accessibilityLabel)).toEqual([
      'No limit',
      'G',
      'PG',
      'PG-13',
      'R',
      'NC-17',
    ]);
    expect(screen.getByRole('radio', { name: 'No limit' })).toBeChecked();
    expect(screen.getByTestId('certification-description')).toHaveTextContent(/Any rating/);
    await fireEvent.press(screen.getByRole('radio', { name: 'PG-13' }));
    expect(screen.getByRole('radio', { name: 'PG-13' })).toBeChecked();
    expect(screen.getByTestId('certification-description')).toHaveTextContent(/inappropriate under 13/);
  });

  it('reports null for No limit', async () => {
    const onChange = jest.fn();
    await render(<CertificationPicker value="R" onChange={onChange} />);
    await fireEvent.press(screen.getByRole('radio', { name: 'No limit' }));
    expect(onChange).toHaveBeenCalledWith(null);
  });
});

describe('ContentLevelPicker / ContentLimitsEditor', () => {
  it('shows the level and its example', async () => {
    const onChange = jest.fn();
    await render(<ContentLevelPicker dimension="VIOLENCE" value="MILD" onChange={onChange} />);
    expect(screen.getByText('Violence & gore')).toBeOnTheScreen();
    expect(screen.getByRole('radio', { name: 'Violence & gore: Mild' })).toBeChecked();
    expect(screen.getByTestId('level-example-VIOLENCE')).toHaveTextContent(LEVEL_EXAMPLES.VIOLENCE.MILD);
    await fireEvent.press(screen.getByRole('radio', { name: 'Violence & gore: None' }));
    expect(onChange).toHaveBeenCalledWith('NONE');
  });

  it('edits all five dimensions in one map', async () => {
    const onChange = jest.fn();
    await render(<ContentLimitsEditor value={{ SEXUAL: 'NONE' }} maxCertification="R" onChange={onChange} />);
    for (const label of ['Violence & gore', 'Sex & nudity', 'Language', 'Drug use', 'Frightening & intense']) {
      expect(screen.getByText(label)).toBeOnTheScreen();
    }
    expect(screen.getByRole('radio', { name: 'Sex & nudity: None' })).toBeChecked();
    expect(screen.getByRole('radio', { name: 'Language: Strong' })).toBeChecked();
    await fireEvent.press(screen.getByRole('radio', { name: 'Language: Moderate' }));
    expect(onChange).toHaveBeenLastCalledWith({ SEXUAL: 'NONE', LANGUAGE: 'MODERATE' });
    await fireEvent.press(screen.getByRole('radio', { name: 'Sex & nudity: Strong' }));
    expect(onChange).toHaveBeenLastCalledWith({});
  });
});

describe('Sex & nudity row follows the maximum rating', () => {
  it.each(['G', 'PG', 'PG_13'] as const)('is hidden for %s', async (cert) => {
    await render(<ContentLimitsEditor value={{}} maxCertification={cert} onChange={jest.fn()} />);
    expect(screen.queryByText('Sex & nudity')).toBeNull();
    expect(screen.getByText('Violence & gore')).toBeOnTheScreen();
    expect(screen.getByText('Frightening & intense')).toBeOnTheScreen();
  });

  it.each(['R', 'NC_17', null] as const)('is shown for %s', async (cert) => {
    await render(<ContentLimitsEditor value={{}} maxCertification={cert} onChange={jest.fn()} />);
    expect(screen.getByText('Sex & nudity')).toBeOnTheScreen();
  });

  it('limitsForCertification drops SEXUAL below R only', () => {
    const limits = { SEXUAL: 'NONE', VIOLENCE: 'MILD' } as const;
    expect(limitsForCertification('PG_13', limits)).toEqual({ VIOLENCE: 'MILD' });
    expect(limitsForCertification('G', limits)).toEqual({ VIOLENCE: 'MILD' });
    expect(limitsForCertification('R', limits)).toEqual(limits);
    expect(limitsForCertification(null, limits)).toEqual(limits);
    expect(showsSexualRow('NC_17')).toBe(true);
    expect(showsSexualRow('PG')).toBe(false);
  });

  it('ComfortFields drops SEXUAL when lowering below R, and the row comes back at Strong', async () => {
    function Harness() {
      const [v, setV] = React.useState<ComfortSettingsDTO>({
        maxCertification: 'R',
        contentLimits: { SEXUAL: 'MILD' },
        allowUnrated: false,
      });
      latest = v;
      return <ComfortFields value={v} onChange={(p) => setV((d) => ({ ...d, ...p }))} />;
    }
    let latest: ComfortSettingsDTO | null = null;
    await render(<Harness />);
    expect(screen.getByRole('radio', { name: 'Sex & nudity: Mild' })).toBeChecked();
    await fireEvent.press(screen.getByRole('radio', { name: 'PG' }));
    expect(screen.queryByText('Sex & nudity')).toBeNull();
    expect(latest!.contentLimits).toEqual({});
    await fireEvent.press(screen.getByRole('radio', { name: 'NC-17' }));
    expect(screen.getByRole('radio', { name: 'Sex & nudity: Strong' })).toBeChecked();
  });
});

describe('FilterBanner', () => {
  // Fixture labels: the backend owns the wording and sends a self-contained label.
  it('renders the server label verbatim with a shield, with no prefix', async () => {
    const filter = makeEffectiveFilter({
      maxCertification: 'PG_13',
      contentLimits: { VIOLENCE: 'MILD' },
      label: 'PG-13 or below · mild violence at most',
    });
    expect(filterBannerText(filter)).toBe('PG-13 or below · mild violence at most');
    await render(<FilterBanner filter={filter} />);
    expect(screen.getByText('PG-13 or below · mild violence at most')).toBeOnTheScreen();
    expect(screen.queryByText(/Filtered to/)).toBeNull();
    expect(screen.getByTestId('icon-shield-checkmark')).toBeOnTheScreen();
    expect(screen.getByTestId('filter-banner').props.accessibilityLabel).toMatch(/most cautious settings/);
    // Never names a member.
    expect(screen.getByTestId('filter-banner').props.accessibilityLabel).not.toMatch(/because|set by/i);
  });

  it('shows the unfiltered label as sent, with an open shield', async () => {
    expect(isUnfiltered(makeEffectiveFilter({ contentLimits: { DRUGS: 'STRONG' } }))).toBe(true);
    expect(isUnfiltered(makeEffectiveFilter({ maxCertification: 'R' }))).toBe(false);
    await render(<FilterBanner filter={makeEffectiveFilter({ label: 'No limits' })} />);
    expect(screen.getByText('No limits')).toBeOnTheScreen();
    expect(screen.getByTestId('icon-shield-outline')).toBeOnTheScreen();
  });

  it.each(['Rated G only', 'Moderate drug content at most', 'No bad language'])('passes "%s" through untouched', (label) => {
    expect(filterBannerText(makeEffectiveFilter({ contentLimits: { LANGUAGE: 'NONE' }, label }))).toBe(label);
  });
});

describe('ConfirmSheet', () => {
  it('confirms, cancels, shows errors and busy state', async () => {
    const onConfirm = jest.fn();
    const onClose = jest.fn();
    const { rerender } = await render(
      <ConfirmSheet visible title="Sure?" message="Really." confirmLabel="Do it" danger onConfirm={onConfirm} onClose={onClose} />,
    );
    expect(screen.getByText('Really.')).toBeOnTheScreen();
    await fireEvent.press(screen.getByRole('button', { name: 'Do it' }));
    expect(onConfirm).toHaveBeenCalled();
    await fireEvent.press(screen.getByRole('button', { name: 'Cancel' }));
    expect(onClose).toHaveBeenCalled();
    await rerender(
      <ConfirmSheet
        visible
        title="Sure?"
        confirmLabel="Do it"
        loading
        error={new NetworkError()}
        onConfirm={onConfirm}
        onClose={onClose}
      />,
    );
    expect(screen.getByRole('alert')).toHaveTextContent(/Couldn't reach/);
    expect(screen.getByRole('button', { name: 'Do it' })).toBeBusy();
    expect(screen.getByRole('button', { name: 'Cancel' })).toBeDisabled();
  });

  it('renders nothing when hidden', async () => {
    await render(<ConfirmSheet visible={false} title="Sure?" confirmLabel="Go" onConfirm={jest.fn()} onClose={jest.fn()} />);
    expect(screen.queryByText('Sure?')).toBeNull();
  });
});
