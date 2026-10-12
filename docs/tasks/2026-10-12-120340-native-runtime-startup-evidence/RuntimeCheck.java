package dev.margintrace.margin_attribution_backend.algorithm.FGW;
import dev.margintrace.margin_attribution_backend.algorithm.BOMFrontier.*;
import dev.margintrace.margin_attribution_backend.algorithm.execution.*;
import dev.margintrace.margin_attribution_backend.algorithm.FGW.*;
import java.util.*;
public class RuntimeCheck {
  public static void main(String[] args) throws Exception {
    for (boolean cuda : new boolean[]{true,false}) {
      var policy = new ExecutionPolicy(true,cuda,null,false);
      try (var selected = new BackendRegistry().resolve("FRONTIER",policy)) {
        var graph = new BomUpwardGraph(new int[]{0,0,1},new int[]{0,1,1},new int[]{1},new int[]{0},new long[]{0,Long.MIN_VALUE},new byte[]{1,1});
        var expected = new BomUpwardEngine().run(graph,Long.MAX_VALUE);
        if (!Arrays.equals(expected,selected.frontier().run(graph,Long.MAX_VALUE))) throw new AssertionError("Frontier mismatch");
        System.out.println((cuda?"CUDA":"OPENCL")+" Frontier exact micro-unit parity PASS");
      }
      for(int mode=0;mode<4;mode++) try(var selected = new BackendRegistry().resolve("FGW",policy)) {
        var config = new FgwEngine.Config(); config.incrementalOtTree=(mode&1)!=0; config.candidateOtPricing=(mode&2)!=0;
        var fixture=FgwFixtures.fixed();
        var expected=new FgwEngine().compare(fixture,config);
        var actual=selected.fgw().compare(fixture,config);
        if(!actual.execution.equals(cuda?"CUDA_DEVICE_EXACT_SIMPLEX":"OPENCL_DEVICE_EXACT_SIMPLEX")) throw new AssertionError(actual.execution);
        for(int i=0;i<expected.starts.size();i++) {
          var x=expected.starts.get(i); var y=actual.starts.get(i);
          if(!x.status.equals(y.status) || Math.abs(x.costs.fgw()-y.costs.fgw())>1e-8) throw new AssertionError("FGW mismatch");
          for(int j=0;j<x.correspondence.length;j++) if(Math.abs(x.correspondence[j]-y.correspondence[j])>1e-8) throw new AssertionError("Correspondence mismatch");
        }
        System.out.println((cuda?"CUDA":"OPENCL")+" FGW mode "+mode+" device simplex/CPU parity PASS");
      }
    }
  }
}
