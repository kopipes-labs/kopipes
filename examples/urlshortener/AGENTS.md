This is an example of how to construct a project using this kotlin aws framework.


Use the exiting /examples/sut project as reference.  That project uses `sut-` as prefix and subsystem name

This project subsystem name and prefix is `urls`.

The implementation language for this example is Java.

This project will have the following components.
 - The base package will be org.myorg.urls
 - common-app/ will have Events, EventCodec and other shared classes.
 - Each service's infra/ builds its stack from the `cdk` module's constructs (`KopipesStack`, `EventHub`,
   `EventsTable`, `KopipesFunction`), in Java.
 - urls-event-hub - Will be similar to sut-event-hub.
 - urls-control-service -  Will be similar to sut-control-service.
   - It will read all the events, collect them and emit new events.
   
 - urls-url-bff - Will be a Rest API. It should have endpoints to
   - Create a URL.
   - Delete a URL.
   - Change a URL.
   This component will insert, mark for deletion and modify records in a dynamodb table. 

   

The events:
  - Create a new short URL.
  - Delete a short URL.
  - Change a URL
  - URL accessed.

The entity is the URL itself. It needs to have a short URL and a long URL. Keep basic stats on the number of times the URL is accessed.



