import org.jstrava.connector.JStravaV3;
import org.jstrava.entities.activity.Activity;
import org.jstrava.entities.activity.Zone;
import org.jstrava.entities.athlete.Athlete;
import org.jstrava.entities.segment.Segment;
import org.jstrava.entities.segment.SegmentEffort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.security.action.OpenFileInputStreamAction;

import java.util.*;

public class Optimizer {

    private static Logger log = LoggerFactory.getLogger(Optimizer.class);
    private static int ACTIVITY_LIMIT = -1;

    private JStravaV3 strava;

    private Map<Segment, Set<Segment>> followingSegments;
    private Map<Segment, List<SegmentEffort>> effortsBySegment;
    private Map<Segment, Integer> avgeTimeBySegment;
    private Set<Segment> endSegments;
    private String[] endPosition = {"51.4842511", "0.0122085"};

    public Optimizer(JStravaV3 strava) {
        this.strava = strava;
    }

    public static void main(String[] args) {
        String accessToken = "9c7ecadd63399334e16aa92d4ac6fde32b96fa0b";
        JStravaV3 strava = new JStravaV3(accessToken);
        Optimizer optimizer = new Optimizer(strava);
        optimizer.findFastestRoute();
    }

    private void findFastestRoute() {
        effortsBySegment = getEffortsBySegment();
        avgeTimeBySegment = getAverageTimeBySegment(effortsBySegment);
        for(Map.Entry<Segment, Integer> entry : avgeTimeBySegment.entrySet()){
            Segment segment = entry.getKey();
            log.debug("Segment \"{}\" with average time {}s followed by segments {}", segment.toString(), entry.getValue(), followingSegments.get(segment));
        }
        String cypher = generateCipher();
        log.debug("\n"+ cypher);
    }

    private String generateCipher() {
        StringBuilder createNodes = new StringBuilder();
        StringBuilder createDistances = new StringBuilder("CREATE");
        int i = 0;
        for (Map.Entry<Segment, Integer> entry : avgeTimeBySegment.entrySet()){
            Segment segment = entry.getKey();
            createNodes.append("CREATE (Segment" + segment.getId() + ":Segment{ " +
                    "name: \"" + segment.getName() + "\", " +
                    "mapsLink: \"http://maps.google.com/?q=" + segment.getStart_latlng()[0] + "," + segment.getStart_latlng()[1] + "\"" +
                    "})\n");
            for(Segment following : followingSegments.get(segment)) {
                createDistances.append("\n\t(Segment" + segment.getId() + ")-[:CONNECTED_TO { distance: " + entry.getValue() + " }]->(Segment" + following.getId() + "),");
            }
            if(endSegments.contains(segment)) {
                createDistances.append("\n\t(Segment" + segment.getId() + ")-[:CONNECTED_TO { distance: " + entry.getValue() + " }]->(SegmentEnd),");
            }
        }
        createNodes.append("CREATE (SegmentEnd:Segment{ " +
                "name: \"End\", " +
                "mapsLink: \"http://maps.google.com/?q=" + endPosition[0] + "," + endPosition[1] + "\"" +
                "})\n");
        String cypher = createNodes.append(createDistances.toString()).toString();
        cypher = cypher.substring(0, cypher.length() - 1);
        return cypher;
    }

    private Map<Segment, Integer> getAverageTimeBySegment(Map<Segment, List<SegmentEffort>> effortsBySegment) {
        Map<Segment, Integer> avgeTimeBySegment = new HashMap<>();
        for (Map.Entry<Segment, List<SegmentEffort>> entry : effortsBySegment.entrySet()) {
            int nEfforts = entry.getValue().size();
            int totalTime = 0;
            for (SegmentEffort effort : entry.getValue()) {
                totalTime += effort.getElapsed_time();
            }
            log.debug("{} efforts for segment {} from {}", nEfforts, entry.getKey(), entry.getKey().getStart_latlng());
            avgeTimeBySegment.put(entry.getKey(), totalTime / nEfforts);
        }
        return avgeTimeBySegment;
    }

    private Map<Segment, List<SegmentEffort>> getEffortsBySegment() {
        List<Activity> activities = strava.getCurrentAthleteActivities();
        Map<Segment, List<SegmentEffort>> effortsBySegment = new HashMap<>();
        int nActivities = activities.size();
        if (ACTIVITY_LIMIT > -1 && nActivities > ACTIVITY_LIMIT) {
            nActivities = ACTIVITY_LIMIT;
        }
        followingSegments = new HashMap<>();
        endSegments = new HashSet<>();
        for (int i = 0; i < nActivities; i++) {
            log.debug("Getting data for activity {} on {}", activities.get(i), activities.get(i).getStart_date());
            Activity activity = strava.findActivity(activities.get(i).getId(), true);
            if(activity.getName().equals("Morning Ride")){
                continue;
            }
            Segment previousSegment = null;
            for (SegmentEffort effort : activity.getSegment_efforts()) {
                Segment segment = effort.getSegment();
                List<SegmentEffort> efforts = effortsBySegment.get(segment);
                if (efforts == null) {
                    efforts = new ArrayList<>();
                    effortsBySegment.put(segment, efforts);
                }
                efforts.add(effort);
                if(!followingSegments.containsKey(segment)){
                    followingSegments.put(segment, new HashSet<Segment>());
                }
                if(previousSegment != null) {
                    followingSegments.get(previousSegment).add(segment);
                }
                previousSegment = segment;
            }
            endSegments.add(previousSegment);
        }
        return effortsBySegment;
    }

}
