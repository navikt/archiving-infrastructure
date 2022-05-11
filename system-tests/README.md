# System-tests
This module contains end-to-end tests and load tests. For a description of the whole archiving system,
see [the documentation](https://github.com/navikt/archiving-infrastructure/wiki).

As is evident by their names, the end-to-end tests will perform various tests on the system as a whole from the outside
and test various behaviours of the system. The load tests will make many simultaneous requests to the system and make
sure that the components can handle the load without breaking.

For more information, see [here](https://github.com/navikt/archiving-infrastructure).

## Debugging load-tests
A cronjob will run the load tests once per week in namespace `team-soknad`. When the load tests fail, the easiest way to
debug is of course to look in the logs. Since several applications are involved, the error could be in several different
places.

### Logs in Kibana
* [Logs for load tests](https://logs.adeo.no/app/discover#/?_g=(filters:!(),refreshInterval:(pause:!t,value:0),time:(from:now-2d,to:now))&_a=(columns:!(message,envclass,level,application,host),filters:!(),interval:auto,query:(language:lucene,query:'namespace:team-soknad%20AND%20application:innsending-system-tests'),sort:!()))
* [Logs for soknadsfillager](https://logs.adeo.no/app/discover#/?_g=(filters:!(),refreshInterval:(pause:!t,value:0),time:(from:now-2d,to:now))&_a=(columns:!(message,envclass,level,application,host),filters:!(),interval:auto,query:(language:lucene,query:'namespace:team-soknad%20AND%20application:soknadsfillager'),sort:!()))
* [Logs for soknadsarkiverer](https://logs.adeo.no/app/discover#/?_g=(filters:!(),refreshInterval:(pause:!t,value:0),time:(from:now-2d,to:now))&_a=(columns:!(message,envclass,level,application,host),filters:!(),interval:auto,query:(language:lucene,query:'namespace:team-soknad%20AND%20application:soknadsarkiverer'),sort:!()))
* [Logs for soknadsmottaker](https://logs.adeo.no/app/discover#/?_g=(filters:!(),refreshInterval:(pause:!t,value:0),time:(from:now-2d,to:now))&_a=(columns:!(message,envclass,level,application,host),filters:!(),interval:auto,query:(language:lucene,query:'namespace:team-soknad%20AND%20application:soknadsmottaker'),sort:!()))
* [Logs for arkiv-mock](https://logs.adeo.no/app/discover#/?_g=(filters:!(),refreshInterval:(pause:!t,value:0),time:(from:now-2d,to:now))&_a=(columns:!(message,envclass,level,application,host),filters:!(),interval:auto,query:(language:lucene,query:'namespace:team-soknad%20AND%20application:arkiv-mock'),sort:!()))

### Logs in kubectl
To view the logs via kubectl, make sure to first run `kubectl config use-context dev-fss`

First list all the pods:
`kubectl get pods -n team-soknad`

Then the logs for a certain pod can be shown by copying its name:
`kubectl -n team-soknad logs soknadsarkiverer-7648f6ddf5-vrdqk`

## Inquiries
Questions regarding the code or the project can be asked to the team by [raising an issue on the repo](https://github.com/navikt/archiving-infrastructure/issues).

### For NAV employees
NAV employees can reach the team by Slack in the channel #teamsoknad
