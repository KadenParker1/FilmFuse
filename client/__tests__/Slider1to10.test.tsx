import { render, screen } from '@testing-library/react-native';
import { Slider1to10 } from '../components/ui/Slider1to10';

describe('Slider1to10', () => {
  it('shows the current score and its label', async () => {
    await render(<Slider1to10 value={8} onChange={() => {}} accessibilityLabel="Your score" />);
    expect(screen.getByTestId('slider-value')).toHaveTextContent('8');
    expect(screen.getByTestId('slider-label')).toHaveTextContent('really like');
  });

  it('exposes an accessible label and value', async () => {
    await render(<Slider1to10 value={3} onChange={() => {}} accessibilityLabel="Sci-fi" />);
    const slider = screen.getByLabelText('Sci-fi');
    expect(slider.props.accessibilityValue).toMatchObject({ min: 1, max: 10, now: 3, text: '3 out of 10, not for me' });
  });
});
