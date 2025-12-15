package ch.unibe.cs.mergeci.service;

import lombok.Getter;
import lombok.Setter;

import java.time.Duration;

@Getter
@Setter
public class RunExecutionTIme {
    private Duration mainExecutionTime;
    private Duration variantsExecutionTime;
}
