/**
 * AI-Based Smart Water Tank Monitoring System
 * --------------------------------------------
 * A simple-reflex / model-based rational agent that monitors the water level
 * of an overhead tank (via an ultrasonic level sensor) and controls a motor
 * pump to prevent both OVERFLOW and DRY-RUN (shortage), while also reacting
 * to stochastic household water consumption and occasional sensor noise.
 *
 * Compile : javac SmartWaterTank.java
 * Run     : java SmartWaterTank
 */

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class SmartWaterTank {

    // ----------------------------- CONFIGURATION ------------------------------
    static final double TANK_CAPACITY_L        = 1000.0; // litres, full tank capacity
    static final double LOW_THRESHOLD          = 25.0;   // % -> pump turns ON at/below this level
    static final double HIGH_THRESHOLD         = 95.0;   // % -> pump turns OFF at/above this level
    static final double PUMP_FLOW_RATE_LPM     = 40.0;   // litres pumped per minute (simulated tick)
    static final double SENSOR_NOISE_STD       = 1.5;    // % standard deviation of sensor noise
    static final int    SIMULATION_TICKS       = 40;     // number of simulated time-steps (minutes)
    static final double DRY_SOURCE_PROBABILITY = 0.03;   // chance the water source runs dry this tick

    // =========================================================================
    // LOG ENTRY (replaces Python tuple in agent.log list)
    // =========================================================================
    static class LogEntry {
        final int     tick;
        final double  percept;
        final boolean pumpOn;

        LogEntry(int tick, double percept, boolean pumpOn) {
            this.tick    = tick;
            this.percept = percept;
            this.pumpOn  = pumpOn;
        }
    }

    // =========================================================================
    // ENVIRONMENT
    // =========================================================================
    /**
     * The environment: physical tank + stochastic household demand.
     */
    static class WaterTankEnvironment {

        private final double capacityL;
        private double       levelL;
        private boolean      pumpOn;
        private boolean      sourceDry;
        private final Random rng;

        WaterTankEnvironment(double capacityL, double startPct, Random rng) {
            this.capacityL = capacityL;
            this.levelL    = capacityL * startPct / 100.0;
            this.pumpOn    = false;
            this.sourceDry = false;
            this.rng       = rng;
        }

        /** True tank level as a percentage [0..100]. */
        double trueLevelPct() {
            return Math.max(0.0, Math.min(100.0, 100.0 * levelL / capacityL));
        }

        /** Return a noisy percept of the tank level (simulates a real sensor). */
        double sensorReading() {
            double noise = gaussNoise(SENSOR_NOISE_STD);
            double raw   = trueLevelPct() + noise;
            return Math.round(Math.max(0.0, Math.min(100.0, raw)) * 10.0) / 10.0;
        }

        /**
         * Advance the environment by one tick given the agent's actuator command.
         * @return consumption (litres) this tick
         */
        double step(boolean pumpCommand) {
            // Random household consumption (stochastic demand): 5..25 litres this tick
            double consumption = 5.0 + rng.nextDouble() * 20.0;
            levelL -= consumption;

            // Occasionally the borewell / municipal source runs dry
            sourceDry = rng.nextDouble() < DRY_SOURCE_PROBABILITY;

            pumpOn = pumpCommand;
            if (pumpCommand && !sourceDry) {
                levelL += PUMP_FLOW_RATE_LPM;
            }

            // physical bounds
            levelL = Math.max(0.0, Math.min(capacityL, levelL));
            return consumption;
        }

        boolean isSourceDry() { return sourceDry; }

        /** Box-Muller transform: Gaussian noise with mean=0 and given std. */
        private double gaussNoise(double std) {
            double u, v;
            do { u = rng.nextDouble(); } while (u == 0.0);
            do { v = rng.nextDouble(); } while (v == 0.0);
            return std * Math.sqrt(-2.0 * Math.log(u)) * Math.cos(2.0 * Math.PI * v);
        }
    }

    // =========================================================================
    // AGENT
    // =========================================================================
    /**
     * Model-based reflex agent with hysteresis control (a rational agent).
     *  - Percept:        noisy water-level reading from the sensor
     *  - Internal state: last known pump status
     *  - Action:         PUMP_ON / PUMP_OFF / NO_CHANGE
     * Rationality: maximises the performance measure (water availability,
     * zero overflow, minimum pump switching, energy saved) given only the
     * partial/noisy percepts it receives.
     */
    static class SmartTankAgent {

        private boolean pumpOn = false;
        private final List<LogEntry> log = new ArrayList<>();

        // performance-measure counters
        private int overflowEvents = 0;
        private int shortageEvents = 0;
        private int pumpSwitches   = 0;

        boolean act(double perceptPct, int tick) {
            boolean previousState = pumpOn;

            // Simple reflex rule-set with hysteresis to avoid rapid switching
            if (perceptPct <= LOW_THRESHOLD) {
                pumpOn = true;
            } else if (perceptPct >= HIGH_THRESHOLD) {
                pumpOn = false;
            }
            // else: keep previous state (model-based memory of last decision)

            if (pumpOn != previousState) {
                pumpSwitches++;
            }

            if (perceptPct >= 99.5) overflowEvents++;
            if (perceptPct <= 2.0)  shortageEvents++;

            log.add(new LogEntry(tick, perceptPct, pumpOn));
            return pumpOn;
        }

        int getOverflowEvents()  { return overflowEvents; }
        int getShortageEvents()  { return shortageEvents; }
        int getPumpSwitches()    { return pumpSwitches;   }
    }

    // =========================================================================
    // SIMULATION RUNNER
    // =========================================================================
    static void runSimulation(Random rng) {
        WaterTankEnvironment env   = new WaterTankEnvironment(TANK_CAPACITY_L, 60, rng);
        SmartTankAgent       agent = new SmartTankAgent();

        // Header
        System.out.printf("%4s | %12s | %10s | %6s | %9s%n",
                "Tick", "SensorLevel%", "TrueLevel%", "Pump", "SourceDry");
        System.out.println("-".repeat(55));

        for (int tick = 1; tick <= SIMULATION_TICKS; tick++) {
            double  percept = env.sensorReading();
            boolean action  = agent.act(percept, tick);
            env.step(action);

            System.out.printf("%4d | %12.1f | %10.1f | %6s | %9s%n",
                    tick,
                    percept,
                    env.trueLevelPct(),
                    action ? "ON" : "OFF",
                    env.isSourceDry() ? "YES" : "no");
        }

        System.out.println("-".repeat(55));
        System.out.println("SIMULATION SUMMARY");
        System.out.printf("  Final tank level      : %.1f%%%n",  env.trueLevelPct());
        System.out.printf("  Pump ON/OFF switches  : %d%n",      agent.getPumpSwitches());
        System.out.printf("  Overflow events       : %d%n",      agent.getOverflowEvents());
        System.out.printf("  Shortage events       : %d%n",      agent.getShortageEvents());

        double efficiency = 100.0
                - (agent.getOverflowEvents() + agent.getShortageEvents()) * 100.0 / SIMULATION_TICKS;
        System.out.printf("  Water-management score: %.1f / 100%n", efficiency);
    }

    // =========================================================================
    // ENTRY POINT
    // =========================================================================
    public static void main(String[] args) {
        Random rng = new Random(42L); // reproducible run for report/demo
        runSimulation(rng);
    }
}
