import { useState } from 'react';
import { Platform } from 'react-native';
import { fireEvent, render, screen } from '@testing-library/react-native';
import { scoreForKey, Slider1to10, SLIDER_PAGE_STEP } from '../components/ui/Slider1to10';
import { slideTo } from '../test-utils/slider';

function Controlled({ start = 5, disabled = false }: { start?: number; disabled?: boolean }) {
  const [v, setV] = useState(start);
  return <Slider1to10 value={v} onChange={setV} accessibilityLabel="Sci-fi" disabled={disabled} />;
}

const key = (k: string) => ({ nativeEvent: { key: k }, preventDefault: jest.fn() });

describe('Slider1to10', () => {
  it('shows the current score and its label', async () => {
    await render(<Slider1to10 value={8} onChange={() => {}} accessibilityLabel="Your score" />);
    expect(screen.getByTestId('slider-value')).toHaveTextContent('8');
    expect(screen.getByTestId('slider-label')).toHaveTextContent('really like');
  });

  it('is one adjustable element with label, range and value text', async () => {
    await render(<Slider1to10 value={3} onChange={() => {}} accessibilityLabel="Sci-fi" />);
    const slider = screen.getByRole('adjustable', { name: 'Sci-fi' });
    expect(slider.props.accessibilityValue).toMatchObject({ min: 1, max: 10, now: 3, text: '3 out of 10, not for me' });
    expect(slider.props.accessibilityActions.map((a: { name: string }) => a.name)).toEqual(['increment', 'decrement']);
    expect(slider.props.focusable).toBe(true);
  });

  it('screen-reader increment / decrement step by one and update the label', async () => {
    await render(<Controlled start={4} />);
    const slider = screen.getByLabelText('Sci-fi');
    await fireEvent(slider, 'accessibilityAction', { nativeEvent: { actionName: 'increment' } });
    expect(screen.getByTestId('slider-value')).toHaveTextContent('5');
    expect(screen.getByTestId('slider-label')).toHaveTextContent('fine');
    expect(screen.getByLabelText('Sci-fi').props.accessibilityValue.now).toBe(5);
    await fireEvent(slider, 'accessibilityAction', { nativeEvent: { actionName: 'decrement' } });
    await fireEvent(slider, 'accessibilityAction', { nativeEvent: { actionName: 'decrement' } });
    expect(screen.getByTestId('slider-value')).toHaveTextContent('3');
  });

  it('clamps actions at 1 and 10', async () => {
    const onChange = jest.fn();
    const { rerender } = await render(<Slider1to10 value={10} onChange={onChange} accessibilityLabel="S" />);
    await fireEvent(screen.getByLabelText('S'), 'accessibilityAction', { nativeEvent: { actionName: 'increment' } });
    await rerender(<Slider1to10 value={1} onChange={onChange} accessibilityLabel="S" />);
    await fireEvent(screen.getByLabelText('S'), 'accessibilityAction', { nativeEvent: { actionName: 'decrement' } });
    expect(onChange).not.toHaveBeenCalled();
  });

  it('dragging still changes the value in whole steps', async () => {
    await render(<Controlled />);
    await slideTo('Sci-fi', 8.6);
    expect(screen.getByTestId('slider-value')).toHaveTextContent('9');
  });

  it('ignores input while disabled', async () => {
    await render(<Controlled disabled />);
    await fireEvent(screen.getByLabelText('Sci-fi'), 'accessibilityAction', { nativeEvent: { actionName: 'increment' } });
    expect(screen.getByTestId('slider-value')).toHaveTextContent('5');
  });

  it('maps the WAI-ARIA slider keys', () => {
    expect(scoreForKey('ArrowRight', 5)).toBe(6);
    expect(scoreForKey('ArrowUp', 5)).toBe(6);
    expect(scoreForKey('ArrowLeft', 5)).toBe(4);
    expect(scoreForKey('ArrowDown', 5)).toBe(4);
    expect(scoreForKey('PageUp', 5)).toBe(5 + SLIDER_PAGE_STEP);
    expect(scoreForKey('PageDown', 2)).toBe(1);
    expect(scoreForKey('PageUp', 9)).toBe(10);
    expect(scoreForKey('Home', 7)).toBe(1);
    expect(scoreForKey('End', 2)).toBe(10);
    expect(scoreForKey('a', 5)).toBeNull();
    expect(scoreForKey('Tab', 5)).toBeNull();
  });

  describe('on web', () => {
    const realOS = Platform.OS;
    beforeAll(() => Object.defineProperty(Platform, 'OS', { get: () => 'web', configurable: true }));
    afterAll(() => Object.defineProperty(Platform, 'OS', { get: () => realOS, configurable: true }));

    it('is a focusable ARIA slider', async () => {
      await render(<Slider1to10 value={6} onChange={() => {}} accessibilityLabel="Cozy" />);
      const slider = screen.getByTestId('slider-1to10');
      expect(slider.props).toMatchObject({
        role: 'slider',
        'aria-label': 'Cozy',
        'aria-valuemin': 1,
        'aria-valuemax': 10,
        'aria-valuenow': 6,
        'aria-valuetext': '6 out of 10, fine',
        tabIndex: 0,
      });
    });

    it('Arrow / Page / Home / End keys change the value and the label, without scrolling', async () => {
      await render(<Controlled start={5} />);
      const slider = () => screen.getByTestId('slider-1to10');
      const right = key('ArrowRight');
      await fireEvent(slider(), 'keyDown', right);
      expect(right.preventDefault).toHaveBeenCalled();
      expect(screen.getByTestId('slider-value')).toHaveTextContent('6');
      expect(slider().props['aria-valuenow']).toBe(6);
      await fireEvent(slider(), 'keyDown', key('End'));
      expect(screen.getByTestId('slider-value')).toHaveTextContent('10');
      expect(screen.getByTestId('slider-label')).toHaveTextContent('all-time');
      await fireEvent(slider(), 'keyDown', key('PageDown'));
      expect(screen.getByTestId('slider-value')).toHaveTextContent(String(10 - SLIDER_PAGE_STEP));
      await fireEvent(slider(), 'keyDown', key('Home'));
      expect(screen.getByTestId('slider-value')).toHaveTextContent('1');
      await fireEvent(slider(), 'keyDown', key('ArrowLeft'));
      expect(screen.getByTestId('slider-value')).toHaveTextContent('1');
      const tab = key('Tab');
      await fireEvent(slider(), 'keyDown', tab);
      expect(tab.preventDefault).not.toHaveBeenCalled();
    });

    it('is not tabbable while disabled', async () => {
      await render(<Controlled disabled />);
      expect(screen.getByTestId('slider-1to10').props.tabIndex).toBe(-1);
      await fireEvent(screen.getByTestId('slider-1to10'), 'keyDown', key('ArrowRight'));
      expect(screen.getByTestId('slider-value')).toHaveTextContent('5');
    });
  });
});
