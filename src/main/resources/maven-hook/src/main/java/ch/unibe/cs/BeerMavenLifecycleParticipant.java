package ch.unibe.cs;

import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.execution.MavenSession;

import javax.inject.Named;
import javax.inject.Singleton;

@Named( "beer")
@Singleton
public class BeerMavenLifecycleParticipant extends AbstractMavenLifecycleParticipant
{
    @Override
    public void afterSessionStart( MavenSession session )
            throws MavenExecutionException
    {
        System.out.println("Open Maven session");
    }
    @Override
    public void afterProjectsRead( MavenSession session )
            throws MavenExecutionException
    {
        System.out.println("Close Maven session");
    }
}