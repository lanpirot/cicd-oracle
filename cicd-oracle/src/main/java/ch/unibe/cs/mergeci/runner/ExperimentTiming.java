package ch.unibe.cs.mergeci.runner;

import lombok.Getter;
import lombok.Setter;

import java.time.Duration;

@Getter
@Setter
public class ExperimentTiming {
    private Duration humanBaselineExecutionTime;
    private Duration variantsExecutionTime;
    /** Baseline seconds after module/test normalization — used as the budget basis. */
    private long normalizedBaselineSeconds;
}
