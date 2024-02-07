package com.greedy.section02.tcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.Scanner;

public class Client {

	public static void main(String[] args) {

		/* 1. 서버의 IP주소와 서버가 정한 포트 번호를 먼저 알아야 한다. */
		int port = 8500;
		
		BufferedReader br = null;			// 한줄 씩 읽기 메소드 제공
		PrintWriter pw = null;				// println으로 출력하는 메소드 제공
		
		try {
			String serverIP = InetAddress.getLocalHost().getHostAddress();
			
			/* 2. 서버의 IP주소와 서버가 정한 포트 번호를 매개변수로 하여 클라이언트용 소켓 객체 생성 */
			Socket socket = new Socket(serverIP, port);
			
			/* 3. 서버와의 입출력 스트림 오픈 */
			if(socket != null) {
				InputStream input = socket.getInputStream();
				OutputStream output = socket.getOutputStream();
				
				br = new BufferedReader(new InputStreamReader(input));
				pw = new PrintWriter(output);
				
				Scanner sc = new Scanner(System.in);
				
				/* 4. 스트림을 통해 쓰고 읽기 */
				do {
					System.out.print("대화 입력: ");
					String message = sc.nextLine();
					pw.println(message);
					pw.flush();
					
					if("exit".equals(message)) {
						break;
					}
					/* 서버로부터 오는 메세지 읽어들임 */
					String recieveMessage = br.readLine();
					System.out.println(recieveMessage);
					
				} while(true);
			}
			
			/* 5. 통신 종료 */
			pw.close();
			br.close();
			socket.close();
			
		} catch (UnknownHostException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

}
