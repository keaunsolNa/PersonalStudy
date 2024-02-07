package com.greedy.section01.inetaddress;

import java.net.InetAddress;
import java.net.UnknownHostException;

public class NerworkTest {

	public static void main(String[] args) throws UnknownHostException {

		/* InetAddress Test */
		InetAddress localIP = InetAddress.getLocalHost();
		
		System.out.println(localIP.getHostAddress());
		System.out.println(localIP.getHostName());
	
		InetAddress naverIP = InetAddress.getByName("www.naver.com");
		
		System.out.println("네이버 서버 IP: " + naverIP.getHostAddress());
		
	}
}
