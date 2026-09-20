package dev.margintrace.margin_attribution_backend.analysis.dto;

import dev.margintrace.margin_attribution_backend.algorithm.model.CsrGraph;
import dev.margintrace.margin_attribution_backend.algorithm.model.Node;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** A CSR snapshot with IDs scoped to one analysis, graph, and array position. */
public record StreamCsrGraph(List<StreamNode> nodes, int[] offset, int[] successors,
                             List<UUID> edgeIds) {
    public record StreamNode(UUID id, int position, String inventoryId,
                             java.math.BigDecimal quantity, java.math.BigDecimal cost) { }

    public static StreamCsrGraph from(CsrGraph graph, UUID analysisId, String role) {
        List<StreamNode> nodes = new ArrayList<>(graph.nodes().length);
        for (int position = 0; position < graph.nodes().length; position++) {
            Node node = graph.nodes()[position];
            nodes.add(new StreamNode(nodeId(analysisId, role, position), position,
                    node.inventoryId(), node.quantity(), node.cost()));
        }
        List<UUID> edgeIds = new ArrayList<>(graph.successors().length);
        for (int index = 0; index < graph.successors().length; index++) {
            edgeIds.add(edgeId(analysisId, role, index));
        }
        return new StreamCsrGraph(List.copyOf(nodes), graph.offset().clone(),
                graph.successors().clone(), List.copyOf(edgeIds));
    }

    public static UUID nodeId(UUID analysisId, String role, int position) {
        return uuidV5(analysisId, role + ":node:" + position);
    }

    public static UUID edgeId(UUID analysisId, String role, int edgeIndex) {
        return uuidV5(analysisId, role + ":edge:" + edgeIndex);
    }

    private static UUID uuidV5(UUID namespace, String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            ByteBuffer namespaceBytes = ByteBuffer.allocate(16);
            namespaceBytes.putLong(namespace.getMostSignificantBits());
            namespaceBytes.putLong(namespace.getLeastSignificantBits());
            digest.update(namespaceBytes.array());
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);
            hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
            ByteBuffer bytes = ByteBuffer.wrap(hash);
            return new UUID(bytes.getLong(), bytes.getLong());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-1 is unavailable", exception);
        }
    }
}
