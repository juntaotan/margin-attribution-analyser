package dev.margintrace.margin_attribution_backend.infrastructure.embedding;

import dev.margintrace.margin_attribution_backend.algorithm.embedding.EmbeddingSettings;
import dev.margintrace.margin_attribution_backend.algorithm.port.EmbeddingProvider.Input;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** Actual process/read-thread lifetime checks with a recording protocol server, not a model substitute. */
class PythonEmbeddingProviderTest {
    @Test void timeoutAndInterruptionReleaseOwnedInferenceWaits() throws Exception {
        String python=System.getenv("MARGIN_EMBEDDING_PYTHON");
        org.junit.jupiter.api.Assumptions.assumeTrue(python!=null,"Python protocol-runtime prerequisite not configured; UNVERIFIED");
        var script=Files.createTempFile("margin-protocol-test-",".py");
        Files.writeString(script,"""
                import json, sys, time
                for line in sys.stdin:
                    request=json.loads(line)
                    if request['operation']=='init':
                        print(json.dumps(dict(metadata=dict(modelId='protocol-test',revision='a'*40,pooling='MEAN',normalization='L2',precision='FP32',backend='CPU',runtime='TEST_ONLY',device='cpu',fallbackReason=None),maximumTokens=512)),flush=True)
                    else:
                        time.sleep(10)
                """);
        try {
            try(var timeout=new PythonEmbeddingProvider(python,script.toString(),"protocol-test","a".repeat(40),EmbeddingSettings.cpu(),1)) {
                var failure=assertThrows(IllegalStateException.class,()->timeout.encode(List.of(new Input("one","steel"))));
                assertEquals("EMBEDDING_TIMEOUT",failure.getMessage());
            }
            try(var cancelled=new PythonEmbeddingProvider(python,script.toString(),"protocol-test","a".repeat(40),EmbeddingSettings.cpu(),30)) {
                var failure=new AtomicReference<RuntimeException>();
                var worker=Thread.ofPlatform().start(()->{try{cancelled.encode(List.of(new Input("one","steel")));}catch(RuntimeException error){failure.set(error);}});
                Thread.sleep(50);worker.interrupt();worker.join(3000);
                assertFalse(worker.isAlive(),"Cancellation must release the owned read wait");assertInstanceOf(CancellationException.class,failure.get());
            }
        } finally {Files.deleteIfExists(script);}
    }
}
