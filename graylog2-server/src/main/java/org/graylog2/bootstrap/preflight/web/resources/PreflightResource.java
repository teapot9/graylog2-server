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
package org.graylog2.bootstrap.preflight.web.resources;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.glassfish.jersey.media.multipart.FormDataBodyPart;
import org.glassfish.jersey.media.multipart.FormDataParam;
import org.graylog.security.certutil.CaService;
import org.graylog.security.certutil.CertConstants;
import org.graylog.security.certutil.ca.exceptions.CACreationException;
import org.graylog.security.certutil.ca.exceptions.KeyStoreStorageException;
import org.graylog2.audit.jersey.NoAuditEvent;
import org.graylog2.bootstrap.preflight.PreflightConstants;
import org.graylog2.bootstrap.preflight.PreflightWebModule;
import org.graylog2.bootstrap.preflight.web.resources.model.CA;
import org.graylog2.bootstrap.preflight.web.resources.model.CertParameters;
import org.graylog2.bootstrap.preflight.web.resources.model.CreateCARequest;
import org.graylog2.cluster.nodes.DataNodeDto;
import org.graylog2.cluster.nodes.DataNodeStatus;
import org.graylog2.cluster.nodes.NodeService;
import org.graylog2.cluster.preflight.DataNodeProvisioningConfig;
import org.graylog2.cluster.preflight.DataNodeProvisioningService;
import org.graylog2.plugin.certificates.RenewalPolicy;
import org.graylog2.plugin.cluster.ClusterConfigService;
import org.graylog2.plugin.rest.ApiError;

import java.io.IOException;
import java.io.StringWriter;
import java.net.URI;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Path(PreflightConstants.API_PREFIX)
@Produces(MediaType.APPLICATION_JSON)
public class PreflightResource {

    private final NodeService<DataNodeDto> nodeService;
    private final DataNodeProvisioningService dataNodeProvisioningService;
    private final CaService caService;
    private final ClusterConfigService clusterConfigService;
    private final String passwordSecret;

    @Inject
    public PreflightResource(final NodeService<DataNodeDto> nodeService,
                             final DataNodeProvisioningService dataNodeProvisioningService,
                             final CaService caService,
                             final ClusterConfigService clusterConfigService,
                             final @Named("password_secret") String passwordSecret) {
        this.nodeService = nodeService;
        this.dataNodeProvisioningService = dataNodeProvisioningService;
        this.caService = caService;
        this.clusterConfigService = clusterConfigService;
        this.passwordSecret = passwordSecret;
    }

    record DataNode(String nodeId, String transportAddress, DataNodeProvisioningConfig.State status, String errorMsg,
                    String hostname, String shortNodeId, DataNodeStatus dataNodeStatus) {}

    @GET
    @Path("/data_nodes")
    @RequiresPermissions(PreflightWebModule.PERMISSION_PREFLIGHT_ONLY)
    public List<DataNode> listDataNodes() {
        final Map<String, DataNodeDto> activeDataNodes = nodeService.allActive();
        final var preflightDataNodes = dataNodeProvisioningService.streamAll().collect(Collectors.toMap(DataNodeProvisioningConfig::nodeId, Function.identity()));

        return activeDataNodes.values().stream().map(n -> {
            final var preflight = preflightDataNodes.get(n.getNodeId());
            return new DataNode(n.getNodeId(),
                    n.getTransportAddress(),
                    preflight != null ? preflight.state() : null, preflight != null ? preflight.errorMsg() : null,
                    n.getHostname(),
                    n.getShortNodeId(),
                    n.getDataNodeStatus());
        }).collect(Collectors.toList());
    }

    @GET
    @Path("/ca")
    @RequiresPermissions(PreflightWebModule.PERMISSION_PREFLIGHT_ONLY)
    public CA get() throws KeyStoreStorageException {
        return caService.get();
    }

    @GET
    @Path("/ca/certificate")
    @Produces(MediaType.TEXT_PLAIN)
    @RequiresPermissions(PreflightWebModule.PERMISSION_PREFLIGHT_ONLY)
    public String getCaCertificate() {
        try {
            return caService.loadKeyStore().map(ks -> {
                final Certificate caPublicKey;
                try {
                    caPublicKey = ks.getCertificate(CertConstants.CA_KEY_ALIAS);
                    return encode(caPublicKey);
                } catch (KeyStoreException | IOException e) {
                    throw new RuntimeException("Failed to obtain CA public key", e);
                }
            }).orElseThrow(() -> new IllegalStateException("CA keystore not available"));
        } catch (KeyStoreException | KeyStoreStorageException | NoSuchAlgorithmException e) {
            throw new RuntimeException("Failed to obtain CA public key", e);
        }
    }

    private static String encode(final Object o) throws IOException {
        var writer = new StringWriter();
        try (JcaPEMWriter jcaPEMWriter = new JcaPEMWriter(writer)) {
            jcaPEMWriter.writeObject(o);
        }
        return writer.toString();
    }

    @POST
    @Path("/ca/create")
    @RequiresPermissions(PreflightWebModule.PERMISSION_PREFLIGHT_ONLY)
    @NoAuditEvent("No Auditing during preflight")
    public Response createCA(@NotNull @Valid CreateCARequest request) throws CACreationException, KeyStoreStorageException, KeyStoreException, NoSuchAlgorithmException {
        // TODO: get validity from preflight UI
        final CA ca = caService.create(request.organization(), CaService.DEFAULT_VALIDITY, passwordSecret.toCharArray());
        return Response.created(URI.create("/api/ca")).entity(ca).build();
    }

    @POST
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Path("/ca/upload")
    @RequiresPermissions(PreflightWebModule.PERMISSION_PREFLIGHT_ONLY)
    @NoAuditEvent("No Auditing during preflight")
    public Response uploadCA(@FormDataParam("password") String password, @FormDataParam("files") List<FormDataBodyPart> bodyParts) {
        try {
            caService.upload(password, bodyParts);
            return Response.ok().build();
        } catch (CACreationException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(ApiError.create(e.getMessage())).build();
        }
    }

    @DELETE
    @Path("/startOver")
    @RequiresPermissions(PreflightWebModule.PERMISSION_PREFLIGHT_ONLY)
    @NoAuditEvent("No Auditing during preflight")
    public void startOver() {
        caService.startOver();
        clusterConfigService.remove(RenewalPolicy.class);
        dataNodeProvisioningService.deleteAll();
    }

    @DELETE
    @Path("/startOver/{nodeID}")
    @RequiresPermissions(PreflightWebModule.PERMISSION_PREFLIGHT_ONLY)
    @NoAuditEvent("No Auditing during preflight")
    public void startOver(@PathParam("nodeID") String nodeID) {
        //TODO:  reset a specific datanode
        dataNodeProvisioningService.delete(nodeID);
    }

    @POST
    @Path("/generate")
    @RequiresPermissions(PreflightWebModule.PERMISSION_PREFLIGHT_ONLY)
    @NoAuditEvent("No Auditing during preflight")
    public void generate() {
        final Map<String, DataNodeDto> activeDataNodes = nodeService.allActive();
        activeDataNodes.values().forEach(node -> dataNodeProvisioningService.changeState(node.getNodeId(), DataNodeProvisioningConfig.State.CONFIGURED));
    }

    @POST
    @Path("/{nodeID}")
    @Consumes(MediaType.APPLICATION_JSON)
    @RequiresPermissions(PreflightWebModule.PERMISSION_PREFLIGHT_ONLY)
    @NoAuditEvent("No Auditing during preflight")
    public void addParameters(@PathParam("nodeID") String nodeID,
                              @NotNull CertParameters params) {
        var cfg = dataNodeProvisioningService.getPreflightConfigFor(nodeID);
        var builder = cfg.map(DataNodeProvisioningConfig::toBuilder).orElse(DataNodeProvisioningConfig.builder().nodeId(nodeID));
        builder.altNames(params.altNames()).validFor(params.validFor());
        dataNodeProvisioningService.save(builder.build());

    }
}
