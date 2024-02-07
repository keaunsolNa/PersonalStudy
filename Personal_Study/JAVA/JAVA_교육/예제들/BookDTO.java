package com.greedy.section01.object.book.dto;

import java.util.Objects;

public class BookDTO {

	/* 책 정보를 관리할 수 있는 DTO 클래스를 작성하자. */
	private int number;							// 책 번호
	private String title;						// 책 제목
	private String author;						// 저자
	private int price;							// 책 가격
	
	public BookDTO() {
	}

	public BookDTO(int number, String title, String author, int price) {
		super();
		this.number = number;
		this.title = title;
		this.author = author;
		this.price = price;
	}

	public int getNumber() {
		return number;
	}

	public void setNumber(int number) {
		this.number = number;
	}

	public String getTitle() {
		return title;
	}

	public void setTitle(String title) {
		this.title = title;
	}

	public String getAuthor() {
		return author;
	}

	public void setAuthor(String author) {
		this.author = author;
	}

	public int getPrice() {
		return price;
	}

	public void setPrice(int price) {
		this.price = price;
	}
	
	/* Object로부터 물려받은 toString()을 확인하기 위해 주석 처리 */
//	@Override
//	public String toString() {
//		return "BookDTO [number=" + number + ", title=" + title + ", author=" + author + ", price=" + price + "]";
//	}
	
	/* 모든 필드에 대한 동등체크를 위한 hashCode()와 equals() */
	@Override
	public int hashCode() {
		return Objects.hash(author, number, price, title);
	}
	
	@Override
	public boolean equals(Object obj) {
		BookDTO other = (BookDTO) obj;
		return Objects.equals(author, other.author) && number == other.number && price == other.price
				&& Objects.equals(title, other.title);
	}
}

