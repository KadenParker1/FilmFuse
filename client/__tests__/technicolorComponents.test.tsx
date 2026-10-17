import { render, screen } from '@testing-library/react-native';
import { LiveIndicator } from '../components/sessions/LiveIndicator';
import { Avatar, Button, Chip, PosterCard, SegmentedControl, Toggle } from '../components/ui';
import { sheetAnimation } from '../components/ui/Sheet';
import { palettes } from '../theme/tokens';

describe('Toggle (bug 8: no default teal Switch)', () => {
  it('themes track and thumb with tokens, including the web-only active colours', async () => {
    await render(<Toggle accessibilityLabel="Post to feed" value onValueChange={jest.fn()} />);
    const sw = screen.getByLabelText('Post to feed');
    expect(sw.props.onTintColor ?? sw.props.trackColor?.true).toBe(palettes.dark.accent);
    expect(sw.props.thumbTintColor ?? sw.props.thumbColor).toBe('#FFFFFF');
    const all = JSON.stringify(sw.props);
    expect(all).not.toMatch(/#009688/i);
  });

  it('passes value, label and handler through', async () => {
    const onValueChange = jest.fn();
    await render(<Toggle accessibilityLabel="Spoilers" value={false} onValueChange={onValueChange} />);
    expect(screen.getByLabelText('Spoilers').props.value).toBe(false);
  });
});

describe('PosterCard certification chip', () => {
  it('shows the rating chip on the artwork when given', async () => {
    await render(<PosterCard title="Arrival" certification="PG_13" score={9} />);
    expect(screen.getByTestId('poster-certification')).toHaveTextContent('PG-13');
    expect(screen.getByLabelText('Rated PG-13')).toBeOnTheScreen();
    expect(screen.getByTestId('poster-score')).toHaveTextContent('9');
  });

  it('shows NR for an unknown rating, and nothing when not passed', async () => {
    const { rerender } = await render(<PosterCard title="Mystery" certification={null} />);
    expect(screen.getByTestId('poster-certification')).toHaveTextContent('NR');
    await rerender(<PosterCard title="Mystery" />);
    expect(screen.queryByTestId('poster-certification')).toBeNull();
  });
});

describe('Technicolor primitives', () => {
  it('buttons use flat fills and large (non-pill) corners', async () => {
    await render(
      <>
        <Button title="Go" />
        <Button title="Later" variant="secondary" />
        <Button title="Delete" variant="danger" />
      </>,
    );
    expect(screen.getByRole('button', { name: 'Go' }).props.className).toMatch(/bg-accent\b.*|rounded-2xl/);
    expect(screen.getByRole('button', { name: 'Later' }).props.className).toMatch(/bg-raised/);
    expect(screen.getByRole('button', { name: 'Delete' }).props.className).toMatch(/bg-danger/);
    expect(screen.getByRole('button', { name: 'Go' }).props.className).not.toMatch(/rounded-full/);
  });

  it('chips and segments fill with the text colour when selected; badges use the second accent', async () => {
    await render(
      <>
        <Chip label="Drama" selected />
        <Chip label="Comedy" />
        <SegmentedControl
          label="Feed"
          value="a"
          onChange={jest.fn()}
          segments={[
            { value: 'a', label: 'Friends' },
            { value: 'b', label: 'Requests', badge: 2 },
          ]}
        />
      </>,
    );
    expect(screen.getByRole('button', { name: 'Drama' }).props.className).toMatch(/bg-text/);
    expect(screen.getByRole('button', { name: 'Comedy' }).props.className).not.toMatch(/bg-text/);
    expect(screen.getByRole('tab', { name: 'Friends' }).props.className).toMatch(/bg-text/);
    expect(screen.getByTestId('segment-badge-b').props.className).toMatch(/bg-accent2/);
  });

  it('avatars fall back to initials on the second accent', async () => {
    await render(<Avatar displayName="Kaden Parker" />);
    expect(screen.getByTestId('avatar-initials')).toHaveTextContent('KP');
    expect(screen.getByLabelText("Kaden Parker's profile photo").props.className).toMatch(/bg-accent2/);
  });

  it('the live pill is green when live and neutral otherwise', async () => {
    const { rerender } = await render(<LiveIndicator mode="live" />);
    expect(screen.getByTestId('live-indicator').props.className).toMatch(/bg-success-soft/);
    await rerender(<LiveIndicator mode="polling" />);
    expect(screen.getByTestId('live-indicator').props.className).not.toMatch(/success/);
  });
});

describe('Sheet animation', () => {
  it('fades on web (a sliding layer exposes the page beneath to clicks) and slides on native', () => {
    expect(sheetAnimation('web')).toBe('fade');
    expect(sheetAnimation('ios')).toBe('slide');
    expect(sheetAnimation('android')).toBe('slide');
    expect(sheetAnimation()).toBe('slide'); // jest runs as ios
  });
});
