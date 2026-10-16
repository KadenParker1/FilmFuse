package com.pm.filmfuse.social;

import com.pm.filmfuse.email.EmailMessage;
import com.pm.filmfuse.social.service.FriendRequestService;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.MailSendException;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Friend-request emails: right recipient, right text, only after commit, never for a no-op. */
class SocialEmailIntegrationTest extends AbstractSocialIntegrationTest {

    @Autowired
    private FriendRequestService friendRequestService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void newRequestEmailsTheRecipientOnly() throws Exception {
        TestUser alice = user("al" + uniquePrefix(), "Alice Liddell");
        TestUser bob = user();

        sendRequest(alice, bob).andExpect(status().isOk());

        EmailMessage message = onlyEmail();
        assertThat(message.to()).isEqualTo(bob.email());
        assertThat(message.subject()).isEqualTo("Alice Liddell (@" + alice.username() + ") sent you a friend request");
        assertThat(message.body()).contains("Alice Liddell (@" + alice.username() + ") sent you a friend request")
                .contains("http://localhost:8081/user/" + alice.id());
    }

    @Test
    void acceptEmailsTheOriginalRequesterOnly() throws Exception {
        TestUser alice = user();
        TestUser bob = user(uniquePrefix(), null);
        sendRequest(alice, bob).andExpect(status().isOk());
        clearEmails();

        accept(bob, alice).andExpect(status().isOk());

        EmailMessage message = onlyEmail();
        assertThat(message.to()).isEqualTo(alice.email());
        assertThat(message.subject()).isEqualTo("@" + bob.username() + " accepted your friend request");
        assertThat(message.body()).contains("accepted your friend request")
                .contains("http://localhost:8081/user/" + bob.id());
    }

    @Test
    void autoAcceptEmailsTheOriginalRequesterAsAnAcceptance() throws Exception {
        TestUser alice = user();
        TestUser bob = user();
        sendRequest(alice, bob).andExpect(status().isOk());
        clearEmails();

        sendRequest(bob, alice).andExpect(status().isOk());

        EmailMessage message = onlyEmail();
        assertThat(message.to()).isEqualTo(alice.email());
        assertThat(message.subject()).endsWith("accepted your friend request");
    }

    @Test
    void noEmailForIdempotentRepeatsDeclinesCancelsBlocksOrInvites() throws Exception {
        TestUser alice = user();
        TestUser bob = user();
        TestUser carol = user();
        sendRequest(alice, bob).andExpect(status().isOk());
        sendRequest(carol, alice).andExpect(status().isOk());
        clearEmails();

        sendRequest(alice, bob).andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/friends/requests/{id}", bob.id()).with(alice.auth()))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/friends/requests/{id}/decline", carol.id()).with(alice.auth()))
                .andExpect(status().isNoContent());
        block(alice, bob);
        String token = com.jayway.jsonpath.JsonPath.read(
                json(mockMvc.perform(post("/api/v1/invites").with(alice.auth()))), "$.token");
        mockMvc.perform(post("/api/v1/invites/{t}/redeem", token).with(carol.auth())).andExpect(status().isOk());

        verify(emailGateway, never()).send(any());
    }

    @Test
    void noEmailOnAConflict() throws Exception {
        TestUser alice = user();
        TestUser bob = user();
        befriend(alice, bob);
        clearEmails();
        sendRequest(alice, bob).andExpect(status().isConflict());
        verify(emailGateway, never()).send(any());
    }

    @Test
    void recipientWithoutAnEmailIsSkipped() throws Exception {
        TestUser alice = user();
        UUID noEmail = UUID.randomUUID();
        // Never signed in through the API, so core-api has no address for them.
        jdbc.update("insert into app.profiles (id, username) values (?, ?)", noEmail, uniquePrefix());

        mockMvc.perform(post("/api/v1/friends/requests").with(alice.auth())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"targetUserId\": \"" + noEmail + "\"}"))
                .andExpect(status().isOk());
        verify(emailGateway, never()).send(any());
    }

    @Test
    void emailIsSentOnlyOnceTheRequestIsCommitted() throws Exception {
        TestUser alice = user();
        TestUser bob = user();
        List<Integer> rowsVisibleToAnotherConnection = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            // Another thread means another connection: it only sees committed data.
            rowsVisibleToAnotherConnection.add(CompletableFuture.supplyAsync(() -> friendshipRows(alice, bob)).get());
            return null;
        }).when(emailGateway).send(any());

        sendRequest(alice, bob).andExpect(status().isOk());

        assertThat(rowsVisibleToAnotherConnection).containsExactly(1);
    }

    @Test
    void rolledBackRequestSendsNothing() throws Exception {
        TestUser alice = user();
        TestUser bob = user();

        transactionTemplate.executeWithoutResult(tx -> {
            friendRequestService.send(alice.id(), bob.id());
            tx.setRollbackOnly();
        });

        assertThat(friendshipRows(alice, bob)).isZero();
        verify(emailGateway, never()).send(any());
    }

    @Test
    void aFailedSendDoesNotFailTheRequest() throws Exception {
        TestUser alice = user();
        TestUser bob = user();
        doThrow(new MailSendException("relay down")).when(emailGateway).send(any());

        sendRequest(alice, bob).andExpect(status().isOk());
        assertThat(friendshipRows(alice, bob)).isEqualTo(1);
    }

    @Test
    void emailGoesToTheLatestAddressFromTheToken() throws Exception {
        TestUser alice = user();
        TestUser bob = user();
        jdbc.update("update app.profiles set email = 'old@example.com' where id = ?", bob.id());
        // Bob's next request carries his (current) token email, which re-syncs the column.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/me")
                .with(bob.auth())).andExpect(status().isOk());

        sendRequest(alice, bob).andExpect(status().isOk());
        assertThat(onlyEmail().to()).isEqualTo(bob.email());
    }

    private EmailMessage onlyEmail() {
        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailGateway, times(1)).send(captor.capture());
        return captor.getValue();
    }

    private void clearEmails() {
        org.mockito.Mockito.clearInvocations(emailGateway);
    }
}
