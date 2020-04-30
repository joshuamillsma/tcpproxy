# tcpproxy
Simple Java NIO based TCP proxy.

The code will forward all traffic on the specified port to the specified destination host and port.

To run the proxy with default values:

  `./gradlew launch`

## Optional java args

| Arg | What | Default |
| --- | ---- | ------- |
| `-h <targethostname>` | Hostname to which the proxy will foward | api.giphy.com |
| `-p <targetport>` | Port to which the proxy will forward | 443 |
| `-l <listenport>` | Local port for incoming connections | 8443 |
| `-s <millis>` | Select loop timeout in ms | 25 |
| `-c <millis>` | Connect timeout in ms | 500 |
| `-b <bytes>` | Bytes to read at a time | 16384 |

## Gradle properties

| Arg | What |
| --- | ---- |
| `args` | Command line parameters for java |
| `debug` | Launch the proxy listening for a java debugger on port 18443 |
| `suspend` | When used with `debug`, start the process suspended |
| `verboseLog` | Set slf4j logging to DEBUG instead of INFO |


For example:

  `gw launch -Pargs="-h www.google.com" -l 8444 -Pdebug -PverboseLog`

  Will start the proxy with java debugging enabled, listening on port 8444, forwarding to www.google.com:443, with DEBUG logging.

## Testing

`./gradlew launch`

In a separate terminal:

`curl https://localhost:8443/v1/gifs/search?q=I&api_key=dc6zaTOxFJmzC -H 'api.giphy.com'`

## Load testing
I used the `siege` package to test the overhead and correctness of the proxy.  The proxy overhead was only noticeable when proxying localhost.


## Caveats
* The proxy just runs as a foreground app, rather than as a full-blown service.
* If you connect to the proxy with a web browser, you will see a certificate error, since `localhost` does not match the target hostname.
* You need to pass in a host header when testing with curl.
* This code cannot do connection pooling to the target hostname, because it does not do any protocol parsing.
* This code could use some more load testing.  The code is single threaded since that is all that should be necessary with non-blocking sockets and epoll.
* Different buffer sizes gave slightly different results.  The highest throughput I saw about about 8 Gbps with a 16K buffer.  More testing would uncover the optimal size.
* Java garbage collection does not seem to be much of a problem, but I would turn on logging and optimize it, given more time.