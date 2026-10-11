#pragma once
#include "fgw_engine.cuh"
#include <cmath>
#include <iomanip>
#include <ostream>

namespace testjson {
inline void number(std::ostream &out, double value) {
    if (std::isfinite(value))
        out << std::setprecision(17) << value;
    else
        out << "null";
}
inline void vector(std::ostream &out, const std::vector<double> &values) {
    out << '[';
    for (std::size_t i = 0; i < values.size(); ++i) {
        if (i)
            out << ',';
        number(out, values[i]);
    }
    out << ']';
}
inline void costs(std::ostream &out, const fgw::Costs &c) {
    out << "{\"material\":";
    number(out, c.material);
    out << ",\"structure\":";
    number(out, c.structure);
    out << ",\"fgw\":";
    number(out, c.fgw);
    out << '}';
}
inline void string(std::ostream &out, const std::string &value) {
    out << '"';
    for (unsigned char c : value) {
        if (c == '"' || c == '\\')
            out << '\\' << c;
        else if (c < 32) {
            const char *digits = "0123456789abcdef";
            out << "\\u00" << digits[c >> 4] << digits[c & 15];
        } else
            out << c;
    }
    out << '"';
}
inline void strings(std::ostream &out, const std::vector<std::string> &values) {
    out << '[';
    for (std::size_t i = 0; i < values.size(); ++i) {
        if (i)
            out << ',';
        string(out, values[i]);
    }
    out << ']';
}
inline void mapping(std::ostream &out, const fgw::SourceMapping &value) {
    out << "{\"productId\":";
    string(out, value.productId);
    out << ",\"instanceId\":";
    string(out, value.instanceId);
    out << ",\"rootIndex\":" << value.rootIndex << ",\"nodes\":[";
    for (std::size_t i = 0; i < value.nodes.size(); ++i) {
        if (i)
            out << ',';
        const auto &node = value.nodes[i];
        out << "{\"occurrenceId\":";
        string(out, node.occurrenceId);
        out << ",\"materialId\":";
        string(out, node.materialId);
        out << ",\"productionOrderId\":";
        string(out, node.productionOrderId);
        out << ",\"quantity\":";
        string(out, node.quantity);
        out << ",\"amount\":";
        string(out, node.amount);
        out << ",\"issueLineIds\":";
        strings(out, node.issueLineIds);
        out << ",\"fifoAllocationIds\":";
        strings(out, node.fifoAllocationIds);
        out << '}';
    }
    out << "]}";
}
inline void result(std::ostream &out, const fgw::Result &r) {
    out << "{\"rows\":" << r.rows << ",\"columns\":" << r.columns << ",\"p\":";
    vector(out, r.p);
    out << ",\"q\":";
    vector(out, r.q);
    out << ",\"material\":";
    vector(out, r.materialDistances);
    out << ",\"d1\":";
    vector(out, r.leftDistances);
    out << ",\"d2\":";
    vector(out, r.rightDistances);
    out << ",\"leftSources\":";
    mapping(out, r.left);
    out << ",\"rightSources\":";
    mapping(out, r.right);
    out << ",\"bestStart\":" << r.bestStart << ",\"totalMilliseconds\":";
    number(out, r.totalMilliseconds);
    out << ",\"executionTimes\":{\"cpuOtMilliseconds\":";
    number(out, r.executionTimes.cpuOtMilliseconds);
    out << ",\"gpuOtMilliseconds\":";
    number(out, r.executionTimes.gpuOtMilliseconds);
    out << ",\"deviceMilliseconds\":";
    number(out, r.executionTimes.deviceMilliseconds);
    out << ",\"synchronizationMilliseconds\":";
    number(out, r.executionTimes.synchronizationMilliseconds);
    out << "},\"starts\":[";
    for (std::size_t s = 0; s < r.starts.size(); ++s) {
        if (s)
            out << ',';
        const auto &x = r.starts[s];
        out << "{\"status\":\"" << fgw::statusName(x.status)
            << "\",\"converged\":" << (x.converged() ? "true" : "false") << ",\"iterations\":" << x.iterations
            << ",\"gap\":";
        number(out, x.finalGap);
        out << ",\"costs\":";
        costs(out, x.costs);
        out << ",\"diagnostic\":";
        string(out, x.diagnostic);
        out << ",\"P\":";
        vector(out, x.correspondence);
        out << ",\"trace\":[";
        for (std::size_t k = 0; k < x.trace.size(); ++k) {
            if (k)
                out << ',';
            const auto &t = x.trace[k];
            out << "{\"updates\":" << t.updates << ",\"costs\":";
            costs(out, t.costs);
            out << ",\"gap\":";
            number(out, t.gap);
            out << ",\"P\":";
            vector(out, t.correspondence);
            out << ",\"G\":";
            vector(out, t.gradient);
            out << ",\"S\":";
            vector(out, t.ot.correspondence);
            out << ",\"delta\":";
            vector(out, t.direction);
            out << ",\"gamma\":";
            number(out, t.step.gamma);
            out << ",\"a\":";
            number(out, t.step.a);
            out << ",\"b\":";
            number(out, t.step.b);
            out << ",\"linearCost\":";
            number(out, t.ot.linearCost);
            out << ",\"dualCost\":";
            number(out, t.ot.dualCost);
            out << ",\"dualViolation\":";
            number(out, t.ot.maximumReducedCostViolation);
            out << ",\"otPivots\":" << t.ot.pivots << ",\"otDegeneratePivots\":" << t.ot.degeneratePivots
                << ",\"otBlandFallback\":" << (t.ot.usedBlandRule ? "true" : "false");
            out << ",\"otFullPricingScans\":" << t.ot.fullPricingScans
                << ",\"otCandidatePricingPasses\":" << t.ot.candidatePricingPasses
                << ",\"otTreeRebuilds\":" << t.ot.treeRebuilds
                << ",\"otIncrementalTreeUpdates\":" << t.ot.incrementalTreeUpdates
                << ",\"otTreeNodesVisited\":" << t.ot.treeNodesVisited
                << ",\"otCycleEdges\":" << t.ot.cycleEdges;
            out << ",\"absoluteCostChange\":";
            number(out, t.absoluteCostChange);
            out << ",\"relativeCostChange\":";
            number(out, t.relativeCostChange);
            out << ",\"rowError\":";
            number(out, t.marginals.row);
            out << ",\"columnError\":";
            number(out, t.marginals.column);
            out << ",\"totalError\":";
            number(out, t.marginals.total);
            out << '}';
        }
        out << "]}";
    }
    out << "]}";
}
} // namespace testjson
