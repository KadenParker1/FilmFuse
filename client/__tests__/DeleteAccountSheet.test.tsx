jest.mock('../api/me', () => ({ useDeleteAccount: jest.fn() }));

import { fireEvent, render, screen } from '@testing-library/react-native';
import { useDeleteAccount } from '../api/me';
import { DeleteAccountSheet } from '../components/profile/DeleteAccountSheet';
import { ApiError } from '../lib/apiClient';

const mockUseDelete = useDeleteAccount as jest.Mock;
let mutation: { mutate: jest.Mock; reset: jest.Mock; isPending: boolean; isError: boolean; error: unknown };

beforeEach(() => {
  mutation = { mutate: jest.fn(), reset: jest.fn(), isPending: false, isError: false, error: null };
  mockUseDelete.mockImplementation(() => mutation);
});

const confirmButton = () => screen.getByRole('button', { name: 'Delete account forever' });

describe('DeleteAccountSheet', () => {
  it('stays disabled until DELETE is typed exactly', async () => {
    await render(<DeleteAccountSheet visible onClose={jest.fn()} />);
    expect(confirmButton()).toBeDisabled();
    await fireEvent.changeText(screen.getByLabelText('Type DELETE to confirm'), 'delete');
    expect(confirmButton()).toBeDisabled();
    await fireEvent.press(confirmButton());
    expect(mutation.mutate).not.toHaveBeenCalled();
    await fireEvent.changeText(screen.getByLabelText('Type DELETE to confirm'), 'DELET');
    expect(confirmButton()).toBeDisabled();
    await fireEvent.changeText(screen.getByLabelText('Type DELETE to confirm'), 'DELETE');
    expect(confirmButton()).toBeEnabled();
    await fireEvent.press(confirmButton());
    expect(mutation.mutate).toHaveBeenCalledTimes(1);
  });

  it('shows server errors inline', async () => {
    mutation.isError = true;
    mutation.error = new ApiError(409, 'Leave your groups first');
    await render(<DeleteAccountSheet visible onClose={jest.fn()} />);
    expect(screen.getByTestId('delete-error')).toHaveTextContent('Leave your groups first');
  });

  it('cancel closes and resets', async () => {
    const onClose = jest.fn();
    await render(<DeleteAccountSheet visible onClose={onClose} />);
    await fireEvent.press(screen.getByRole('button', { name: 'Cancel' }));
    expect(onClose).toHaveBeenCalled();
    expect(mutation.reset).toHaveBeenCalled();
  });

  it('cannot be closed while deleting', async () => {
    mutation.isPending = true;
    const onClose = jest.fn();
    await render(<DeleteAccountSheet visible onClose={onClose} />);
    await fireEvent.press(screen.getByRole('button', { name: 'Cancel' }));
    expect(onClose).not.toHaveBeenCalled();
  });

  it('renders nothing when hidden', async () => {
    await render(<DeleteAccountSheet visible={false} onClose={jest.fn()} />);
    expect(screen.queryByText('Delete your account?')).toBeNull();
  });
});
