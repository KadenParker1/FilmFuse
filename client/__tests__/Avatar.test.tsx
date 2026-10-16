import { fireEvent, render, screen } from '@testing-library/react-native';
import { Avatar } from '../components/ui/Avatar';
import { initialsFor } from '../components/ui/initials';

describe('initialsFor', () => {
  it.each([
    ['Ada Lovelace', null, 'AL'],
    ['  ada   king  lovelace ', null, 'AL'],
    ['Cher', 'cher99', 'C'],
    [null, 'popcorn_paula', 'P'],
    ['', '@zed', 'Z'],
    [null, null, '?'],
    ['élodie', null, 'É'],
  ])('%p / %p → %p', (displayName, username, expected) => {
    expect(initialsFor(displayName, username)).toBe(expected);
  });
});

describe('Avatar', () => {
  it('shows initials when there is no photo', async () => {
    await render(<Avatar displayName="Ada Lovelace" />);
    expect(screen.getByTestId('avatar-initials')).toHaveTextContent('AL');
    expect(screen.queryByTestId('avatar-image')).toBeNull();
    expect(screen.getByRole('image', { name: "Ada Lovelace's profile photo" })).toBeOnTheScreen();
  });

  it('shows the photo when a URI is given', async () => {
    await render(<Avatar uri="https://example.com/a.jpg" username="ada" />);
    expect(screen.getByTestId('avatar-image')).toBeOnTheScreen();
    expect(screen.queryByTestId('avatar-initials')).toBeNull();
  });

  it('falls back to initials when the photo fails to load', async () => {
    await render(<Avatar uri="https://example.com/broken.jpg" username="ada" />);
    await fireEvent(screen.getByTestId('avatar-image'), 'error', { nativeEvent: { error: 'boom' } });
    expect(screen.getByTestId('avatar-initials')).toHaveTextContent('A');
  });
});
