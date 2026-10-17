import { fireEvent, screen, within } from '@testing-library/react-native';

/**
 * Drags the Slider1to10 named `label` to `value`. The accessible element is the adjustable wrapper;
 * the drag track inside it is hidden from assistive tech, so it's found by test id.
 */
export function slideTo(label: string, value: number) {
  const slider = screen.getByLabelText(label);
  return fireEvent(within(slider).getByTestId('slider-track', { includeHiddenElements: true }), 'valueChange', value);
}
