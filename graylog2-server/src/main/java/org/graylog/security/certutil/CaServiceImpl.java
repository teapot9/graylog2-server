/*
 * Copyright (C) 2020 Graylog, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the Server Side Public License, version 1,
 * as published by MongoDB, Inc.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * Server Side Public License for more details.
 *
 * You should have received a copy of the Server Side Public License
 * along with this program. If not, see
 * <http://www.mongodb.com/licensing/server-side-public-license>.
 */
package org.graylog.security.certutil;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.glassfish.jersey.media.multipart.BodyPart;
import org.glassfish.jersey.media.multipart.FormDataBodyPart;
import org.graylog.security.certutil.ca.CAKeyPair;
import org.graylog.security.certutil.ca.PemCaReader;
import org.graylog.security.certutil.ca.exceptions.CACreationException;
import org.graylog.security.certutil.ca.exceptions.KeyStoreStorageException;
import org.graylog.security.certutil.keystore.storage.KeystoreFileStorage;
import org.graylog.security.certutil.keystore.storage.KeystoreMongoStorage;
import org.graylog.security.certutil.keystore.storage.location.KeystoreMongoLocation;
import org.graylog2.Configuration;
import org.graylog2.bootstrap.preflight.web.resources.model.CA;
import org.graylog2.bootstrap.preflight.web.resources.model.CAType;
import org.graylog2.cluster.certificates.CertificatesService;
import org.graylog2.events.ClusterEventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.graylog.security.certutil.CertConstants.CA_KEY_ALIAS;
import static org.graylog.security.certutil.CertConstants.PKCS12;

@Singleton
public class CaServiceImpl implements CaService {
    private static final Logger LOG = LoggerFactory.getLogger(CaServiceImpl.class);



    private final KeystoreMongoStorage mongoKeystore;
    private final KeystoreFileStorage fileKeystore;

    private final PemCaReader pemCaReader;
    private final CaConfiguration configuration;
    private final CertificatesService certificatesService;
    private final String passwordSecret;

    private final ClusterEventBus eventBus;

    @Inject
    public CaServiceImpl(KeystoreMongoStorage keystoreMongoStorage, KeystoreFileStorage keystoreFileStorage, final Configuration configuration,
                         final PemCaReader pemCaReader,
                         final CertificatesService certificatesService,
                         final @Named("password_secret") String passwordSecret, ClusterEventBus eventBus) {
        this.mongoKeystore = keystoreMongoStorage;
        this.fileKeystore = keystoreFileStorage;
        this.pemCaReader = pemCaReader;
        this.configuration = configuration;
        this.certificatesService = certificatesService;
        this.passwordSecret = configuration.getCaPassword() != null ? configuration.getCaPassword() : passwordSecret;
        this.eventBus = eventBus;
    }

    @Override
    public CA get() throws KeyStoreStorageException {
        if (configuration.configuredCaExists()) {
            return new CA("local CA", CAType.LOCAL);
        } else {
            var keystore = mongoKeystore.readKeyStore(KeystoreMongoLocation.certificateAuthority(), passwordSecret.toCharArray());
            return keystore.map(c -> new CA(KeystoreMongoLocation.CA_KEYSTORE_ID, CAType.GENERATED)).orElse(null);
        }
    }

    @Override
    public CA create(final String organization, final Integer daysValid, char[] password) throws CACreationException, KeyStoreStorageException, KeyStoreException {
        final Duration certificateValidity = Duration.ofDays(daysValid == null || daysValid == 0 ? DEFAULT_VALIDITY : daysValid);
        KeyStore keyStore = CAKeyPair.create(organization, passwordSecret.toCharArray(), certificateValidity).toKeyStore();
        mongoKeystore.writeKeyStore(KeystoreMongoLocation.certificateAuthority(), keyStore, passwordSecret.toCharArray(), password);
        LOG.debug("Generated a new CA.");
        triggerCaChangedEvent();
        return get();
    }

    @Override
    public void upload(@Nullable String password, List<FormDataBodyPart> parts) throws CACreationException {
        final var passwordCharArray = password == null ? null : password.toCharArray();
        // TODO: if the upload consists of more than one file, handle accordingly
        // or: decide that it's always only one file containing all certificates
        try {
            KeyStore keyStore = KeyStore.getInstance(PKCS12, "BC");
            keyStore.load(null, null);
            for (BodyPart part : parts) {
                InputStream is = part.getEntityAs(InputStream.class);
                byte[] bytes = is.readAllBytes();
                String pem = new String(bytes, StandardCharsets.UTF_8);
                // Test, if upload is PEM file, must contain at least a certificate
                if (pem.contains("-----BEGIN CERTIFICATE")) {
                    var ca = pemCaReader.readCA(pem, password);
                    keyStore.setKeyEntry(CA_KEY_ALIAS, ca.privateKey(), passwordCharArray, ca.certificates().toArray(new Certificate[0]));
                } else {
                    ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
                    keyStore.load(bais, passwordCharArray);
                }
            }
            mongoKeystore.writeKeyStore(KeystoreMongoLocation.certificateAuthority(), keyStore, passwordCharArray, passwordSecret.toCharArray());
            triggerCaChangedEvent();
        } catch (IOException | KeyStoreStorageException | NoSuchAlgorithmException | CertificateException |
                 KeyStoreException | NoSuchProviderException ex) {
            LOG.error("Could not write CA: " + ex.getMessage(), ex);
            throw new CACreationException("Could not write CA: " + ex.getMessage(), ex);
        }
    }

    @Override
    public void startOver() {
        certificatesService.removeCert(KeystoreMongoLocation.certificateAuthority());
    }


    private void triggerCaChangedEvent() {
        eventBus.post(new CertificateAuthorityChangedEvent());
    }

    @Override
    public Optional<KeyStore> loadKeyStore() throws KeyStoreStorageException {
        if (configuration.configuredCaExists()) {
            return fileKeystore.readKeyStore(configuration.getCaKeystoreFile(), configuration.getCaPassword().toCharArray());
        } else {
            return mongoKeystore.readKeyStore(KeystoreMongoLocation.certificateAuthority(), passwordSecret.toCharArray());
        }
    }
}
