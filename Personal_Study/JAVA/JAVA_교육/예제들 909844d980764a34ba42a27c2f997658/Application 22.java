package com.greedy.section02.daemonthread;

import javax.swing.JOptionPane;

public class Application {
	public static void main(String[] args) {
		Thread th = new CountDown();

		th.setDaemon(true);
		th.start();
		
		System.out.println(JOptionPane.showInputDialog("아무 문자열이나 입력해 보세요 : "));
		
		System.out.println("메인 스레드 종료!");
	}
}
