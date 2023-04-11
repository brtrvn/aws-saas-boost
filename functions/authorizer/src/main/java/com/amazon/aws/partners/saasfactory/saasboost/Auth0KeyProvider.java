package com.amazon.aws.partners.saasfactory.saasboost;

import com.auth0.jwk.JwkException;
import com.auth0.jwk.JwkProvider;
import com.auth0.jwk.JwkProviderBuilder;
import com.auth0.jwt.interfaces.RSAKeyProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.MalformedURLException;
import java.net.URL;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

public class Auth0KeyProvider implements RSAKeyProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(Auth0KeyProvider.class);
    private static final String AUTH0_DOMAIN_HOST = System.getenv("AUTH0_DOMAIN_HOST");
    private final JwkProvider keyProvider;

    public Auth0KeyProvider() {
        if (Utils.isBlank(AUTH0_DOMAIN_HOST)) {
            throw new IllegalStateException("Missing required environment variable AUTH0_DOMAIN_HOST");
        }
        keyProvider = new JwkProviderBuilder(jwksUrl()).build();
    }

    @Override
    public RSAPublicKey getPublicKeyById(String kid) {
        try {
            return (RSAPublicKey) keyProvider.get(kid).getPublicKey();
        } catch (JwkException e) {
            LOGGER.error(Utils.getFullStackTrace(e));
            throw new RuntimeException(e);
        }
    }

    @Override
    public RSAPrivateKey getPrivateKey() {
        return null;
    }

    @Override
    public String getPrivateKeyId() {
        return null;
    }

    // https://auth0.com/docs/secure/tokens/json-web-tokens/json-web-key-sets
    protected static URL jwksUrl() {
        URL url = null;
        try {
            url = new URL(AUTH0_DOMAIN_HOST + "/.well-known/jwks.json");
        } catch (MalformedURLException e) {
            LOGGER.error(Utils.getFullStackTrace(e));
        }
        return url;
    }
}
