package com.pm.filmfuse.email;

/**
 * The one port every outbound email goes through. No provider SDK type crosses it, so changing
 * relay is configuration ({@code spring.mail.*}), never a call-site change.
 */
public interface EmailGateway {

    void send(EmailMessage message);
}
