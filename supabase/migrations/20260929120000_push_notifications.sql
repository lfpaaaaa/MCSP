-- Push notifications for new group messages. Devices register their Firebase Cloud Messaging
-- token through register_device_token; a trigger on messages asks the notify-message Edge
-- Function (through pg_net) to notify the other members' devices. The function's address and the
-- key it expects are read from Vault, so nothing secret lives in this file; until both secrets
-- exist the trigger does nothing and messages are stored as before.

CREATE EXTENSION IF NOT EXISTS pg_net;

CREATE TABLE public.device_tokens (
    token TEXT PRIMARY KEY CHECK (char_length(token) BETWEEN 20 AND 4096),
    user_id UUID NOT NULL REFERENCES auth.users (id) ON DELETE CASCADE,
    platform TEXT NOT NULL DEFAULT 'android' CHECK (platform IN ('android')),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE public.device_tokens IS
    'Firebase Cloud Messaging tokens of signed-in devices. A token belongs to the user who registered it last.';

CREATE INDEX device_tokens_user_id_idx ON public.device_tokens (user_id);

ALTER TABLE public.device_tokens ENABLE ROW LEVEL SECURITY;

-- Users see only their own tokens; every change goes through the functions below.
CREATE POLICY "Users can see their own device tokens"
    ON public.device_tokens
    FOR SELECT
    TO authenticated
    USING (user_id = (SELECT auth.uid()));

GRANT SELECT ON public.device_tokens TO authenticated;

-- ---------------------------------------------------------------------------
-- Registering and removing tokens
-- ---------------------------------------------------------------------------

CREATE FUNCTION private.register_device_token(p_token TEXT)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
    v_user_id UUID := auth.uid();
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'not_authenticated' USING ERRCODE = '42501';
    END IF;

    -- A device that changes hands takes its token to the new user.
    INSERT INTO public.device_tokens (token, user_id)
    VALUES (p_token, v_user_id)
    ON CONFLICT (token) DO UPDATE
    SET user_id = EXCLUDED.user_id,
        updated_at = now();
END;
$$;

CREATE FUNCTION public.register_device_token(p_token TEXT)
RETURNS VOID
LANGUAGE sql
SECURITY INVOKER
SET search_path = ''
AS $$
    SELECT private.register_device_token(p_token);
$$;

COMMENT ON FUNCTION public.register_device_token(TEXT) IS
    'Registers the calling device for push notifications to the signed-in user.';

CREATE FUNCTION private.unregister_device_token(p_token TEXT)
RETURNS VOID
LANGUAGE sql
SECURITY DEFINER
SET search_path = ''
AS $$
    DELETE FROM public.device_tokens
    WHERE token = p_token
      AND user_id = (SELECT auth.uid());
$$;

CREATE FUNCTION public.unregister_device_token(p_token TEXT)
RETURNS VOID
LANGUAGE sql
SECURITY INVOKER
SET search_path = ''
AS $$
    SELECT private.unregister_device_token(p_token);
$$;

COMMENT ON FUNCTION public.unregister_device_token(TEXT) IS
    'Stops push notifications to this device, for example at sign-out. Only the owner of the token can remove it.';

-- ---------------------------------------------------------------------------
-- Telling the notify-message function about new messages
-- ---------------------------------------------------------------------------

CREATE FUNCTION private.notify_new_message()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
    v_url TEXT;
    v_key TEXT;
BEGIN
    BEGIN
        SELECT decrypted_secret INTO v_url FROM vault.decrypted_secrets WHERE name = 'project_url';
        SELECT decrypted_secret INTO v_key FROM vault.decrypted_secrets WHERE name = 'notify_message_key';

        IF v_url IS NULL OR v_key IS NULL THEN
            RETURN NEW;
        END IF;

        PERFORM net.http_post(
            url := v_url || '/functions/v1/notify-message',
            body := jsonb_build_object(
                'message', jsonb_build_object(
                    'id', NEW.id,
                    'group_id', NEW.group_id,
                    'sender_id', NEW.sender_id
                )
            ),
            headers := jsonb_build_object(
                'Content-Type', 'application/json',
                'x-notify-key', v_key
            ),
            timeout_milliseconds := 5000
        );
    EXCEPTION WHEN OTHERS THEN
        -- A notification that cannot be requested must never stop the message from being stored.
        RAISE WARNING 'notify_new_message: %', SQLERRM;
    END;

    RETURN NEW;
END;
$$;

COMMENT ON FUNCTION private.notify_new_message() IS
    'After a message is stored, asks the notify-message Edge Function to push it to the other members. Reads project_url and notify_message_key from Vault.';

CREATE TRIGGER messages_notify_new_message
    AFTER INSERT ON public.messages
    FOR EACH ROW
    EXECUTE FUNCTION private.notify_new_message();

-- ---------------------------------------------------------------------------
-- Privileges
-- ---------------------------------------------------------------------------

-- The public wrappers run as the caller, so signed-in users need EXECUTE on the private functions
-- too; the trigger function runs only from the trigger.
REVOKE ALL ON FUNCTION private.register_device_token(TEXT) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION private.unregister_device_token(TEXT) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION private.notify_new_message() FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION private.register_device_token(TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION private.unregister_device_token(TEXT) TO authenticated;

REVOKE ALL ON FUNCTION public.register_device_token(TEXT) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.unregister_device_token(TEXT) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.register_device_token(TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.unregister_device_token(TEXT) TO authenticated;
